package com.example.jwtcache

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import org.slf4j.LoggerFactory

import java.io.StringWriter
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Lightweight HTTP server exposing:
 *   GET  /healthz          – liveness
 *   GET  /readyz           – readiness (token cache reachable)
 *   GET  /metrics          – Prometheus scrape endpoint
 *   GET  /token-stats      – human-readable cache stats
 *   POST /force-refresh    – invalidate cache and force a new token on next request
 *                            (requires the X-Force-Refresh-Token header — see
 *                            forceRefreshToken below)
 */
class MetricsServer(
    tokenCache: GoogleJwtTokenCache,
    interface: String = "0.0.0.0",
    port: Int = 8080,
    forceRefreshToken: Option[String] = sys.env.get("FORCE_REFRESH_TOKEN")
)(implicit system: ActorSystem[_], ec: ExecutionContext) {

  private val log = LoggerFactory.getLogger(getClass)

  if (forceRefreshToken.isEmpty) {
    log.warn(
      "FORCE_REFRESH_TOKEN is not set — POST /force-refresh will reject every request until it is configured"
    )
  }

  /** Public so unit tests can exercise the real routes. */
  val routes: Route =
    path("healthz") {
      get {
        complete(StatusCodes.OK -> "OK")
      }
    } ~
      path("readyz") {
        get {
          // Bound how long a hung/slow Google token call can hold this
          // request open. Without this, a stuck refresh falls back to
          // Akka HTTP's default request-timeout (20s) before the caller
          // gets any response at all — worse for a readiness probe than
          // a fast, explicit failure.
          withRequestTimeout(3.seconds) {
            onComplete(tokenCache.getToken) {
              case Success(_)  => complete(StatusCodes.OK -> "READY")
              case Failure(ex) =>
                complete(StatusCodes.ServiceUnavailable -> s"NOT_READY: ${ex.getMessage}")
            }
          }
        }
      } ~
      path("metrics") {
        get {
          complete {
            val writer = new StringWriter()
            TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples())
            HttpEntity(ContentTypes.`text/plain(UTF-8)`, writer.toString)
          }
        }
      } ~
      path("token-stats") {
        get {
          complete {
            val stats = tokenCache.stats
            val json = stats
              .map {
                case (k, v: String)  => s""""$k": "$v""""
                case (k, v: Boolean) => s""""$k": $v"""
                case (k, v)          => s""""$k": $v"""
              }
              .mkString("{", ", ", "}")
            HttpEntity(ContentTypes.`application/json`, json)
          }
        }
      } ~
      path("force-refresh") {
        post {
          // Require a shared-secret header so this can't be triggered by
          // anyone who can reach the port. Previously unauthenticated —
          // repeated calls could invalidate the cache fast enough to
          // force /readyz into real Google API calls on every probe,
          // which could tip /readyz into failing and trigger a pod
          // restart loop. Always return the same generic Forbidden
          // regardless of *why* it failed (missing header, wrong value,
          // or feature not configured) so the response itself doesn't
          // leak which case applied.
          optionalHeaderValueByName("X-Force-Refresh-Token") { providedOpt =>
            (forceRefreshToken, providedOpt) match {
              case (Some(expected), Some(provided)) if provided == expected =>
                tokenCache.invalidate()
                complete(StatusCodes.OK -> "Token cache invalidated – next request will refresh")
              case _ =>
                complete(StatusCodes.Forbidden -> "Forbidden")
            }
          }
        }
      }

  def start(): Future[Http.ServerBinding] = {
    val binding = Http().newServerAt(interface, port).bind(routes)
    binding.onComplete {
      case Success(b) =>
        log.info(
          "Metrics server listening on http://{}:{}/",
          b.localAddress.getHostString,
          b.localAddress.getPort
        )
      case Failure(ex) => log.error("Failed to start metrics server", ex)
    }
    binding
  }
}
