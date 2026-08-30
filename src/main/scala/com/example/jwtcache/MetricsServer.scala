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
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Lightweight HTTP server exposing:
 *   GET /healthz          – liveness
 *   GET /readyz           – readiness (token cache reachable)
 *   GET /metrics          – Prometheus scrape endpoint
 *   GET /token-stats      – human-readable cache stats
 *   POST /force-refresh   – invalidate cache and force a new token
 */
class MetricsServer(
    tokenCache: GoogleJwtTokenCache,
    interface: String = "0.0.0.0",
    port: Int = 8080
)(implicit system: ActorSystem[_], ec: ExecutionContext) {

  private val log = LoggerFactory.getLogger(getClass)

  private val route: Route =
    path("healthz") {
      get {
        complete(StatusCodes.OK -> "OK")
      }
    } ~
      path("readyz") {
        get {
          onComplete(tokenCache.getToken) {
            case Success(_)  => complete(StatusCodes.OK -> "READY")
            case Failure(ex) =>
              complete(StatusCodes.ServiceUnavailable -> s"NOT_READY: ${ex.getMessage}")
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
              .map { case (k, v) => s""""$k": $v""" }
              .mkString("{", ", ", "}")
            HttpEntity(ContentTypes.`application/json`, json)
          }
        }
      } ~
      path("force-refresh") {
        post {
          tokenCache.invalidate()
          complete(StatusCodes.OK -> "Token cache invalidated – next request will refresh")
        }
      }

  def start(): Future[Http.ServerBinding] = {
    val binding = Http().newServerAt(interface, port).bind(route)
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
