package com.example.jwtcache

import com.github.benmanes.caffeine.cache.{Caffeine, LoadingCache}
import com.google.auth.oauth2.{GoogleCredentials, IdTokenCredentials, IdTokenProvider}
import io.prometheus.client.{Counter, Gauge, Histogram}
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * Thread-safe, observable cache for Google access / ID tokens.
 *
 * - Uses Caffeine for automatic expiry & refresh
 * - Exposes Prometheus metrics
 * - Supports both Access Tokens and ID Tokens (JWT)
 */
class GoogleJwtTokenCache(
    scopes: Seq[String] = Seq("https://www.googleapis.com/auth/cloud-platform"),
    audience: Option[String] = None,
    cacheTtlMinutes: Long = 50,
    refreshSkewSeconds: Long = 60
)(implicit ec: ExecutionContext) {

  private val log = LoggerFactory.getLogger(getClass)

  // ---------- Prometheus metrics ----------
  private val tokenRequests = Counter
    .build()
    .name("google_jwt_token_requests_total")
    .help("Total token requests (cache hit + miss)")
    .labelNames("result")
    .register()

  private val tokenRefreshDuration = Histogram
    .build()
    .name("google_jwt_token_refresh_seconds")
    .help("Time spent refreshing a Google token")
    .register()

  private val cacheSize = Gauge
    .build()
    .name("google_jwt_cache_size")
    .help("Current number of entries in the token cache")
    .register()

  private val lastRefreshTimestamp = Gauge
    .build()
    .name("google_jwt_last_refresh_timestamp_seconds")
    .help("Unix timestamp of the last successful token refresh")
    .register()

  // ---------- Credentials ----------
  private lazy val credentials: GoogleCredentials = {
    val base = Try(GoogleCredentials.getApplicationDefault())
      .getOrElse {
        log.warn("Application Default Credentials not found – using mock credentials for local/dev")
        new MockGoogleCredentials()
      }
    base.createScoped(scopes: _*)
  }

  // ---------- Caffeine cache ----------
  private val cache: LoadingCache[String, CachedToken] = Caffeine
    .newBuilder()
    .expireAfterWrite(cacheTtlMinutes, TimeUnit.MINUTES)
    .maximumSize(10)
    .recordStats()
    .build((_: String) => fetchFreshToken())

  private case class CachedToken(value: String, expiresAtMs: Long)

  private def fetchFreshToken(): CachedToken = {
    val timer = tokenRefreshDuration.startTimer()
    try {
      log.info("Refreshing Google token (audience={})", audience.getOrElse("access-token"))

      credentials.refreshIfExpired()

      val (tokenValue, expiresInMs) = audience match {
        case Some(aud) =>
          val idTokenCreds = IdTokenCredentials
            .newBuilder()
            .setIdTokenProvider(credentials.asInstanceOf[IdTokenProvider])
            .setTargetAudience(aud)
            .build()
          val token = idTokenCreds.refreshAccessToken()
          (
            token.getTokenValue,
            Option(token.getExpirationTime)
              .map(_.getTime - System.currentTimeMillis())
              .getOrElse(3600_000L)
          )

        case None =>
          val token = credentials.getAccessToken
          (
            token.getTokenValue,
            Option(token.getExpirationTime)
              .map(_.getTime - System.currentTimeMillis())
              .getOrElse(3600_000L)
          )
      }

      lastRefreshTimestamp.set(System.currentTimeMillis() / 1000.0)
      cacheSize.set(1)
      tokenRequests.labels("miss").inc()

      log.info("Token refreshed successfully, expires in ~{}s", expiresInMs / 1000)
      CachedToken(tokenValue, System.currentTimeMillis() + expiresInMs - (refreshSkewSeconds * 1000))
    } catch {
      case ex: Exception =>
        tokenRequests.labels("error").inc()
        log.error("Failed to refresh Google token", ex)
        throw ex
    } finally {
      timer.observeDuration()
    }
  }

  /** Non-blocking get – returns cached token or triggers refresh */
  def getToken: Future[String] = Future {
    val token = cache.get("google-token")
    tokenRequests.labels("hit").inc()
    cacheSize.set(cache.estimatedSize())
    token.value
  }

  /** Force invalidation (e.g. after receiving 401 from Kafka) */
  def invalidate(): Unit = {
    log.info("Invalidating token cache")
    cache.invalidateAll()
    cacheSize.set(0)
  }

  /** Current cache stats for health endpoint */
  def stats: Map[String, Any] = {
    val s = cache.stats()
    Map(
      "hitCount"      -> s.hitCount(),
      "missCount"     -> s.missCount(),
      "loadSuccess"   -> s.loadSuccessCount(),
      "loadFailure"   -> s.loadFailureCount(),
      "evictionCount" -> s.evictionCount(),
      "estimatedSize" -> cache.estimatedSize()
    )
  }
}

/**
 * Minimal mock credentials used when running locally without GCP ADC.
 * Returns a deterministic fake JWT so the rest of the pipeline can be exercised.
 */
class MockGoogleCredentials extends GoogleCredentials {
  override def refreshAccessToken(): com.google.auth.oauth2.AccessToken = {
    val fakeJwt =
      "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9." +
        "eyJpc3MiOiJodHRwczovL2FjY291bnRzLmdvb2dsZS5jb20iLCJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6Ik1vY2sgVXNlciIsImlhdCI6MTUxNjIzOTAyMiwiZXhwIjo5OTk5OTk5OTk5fQ." +
        "mock-signature"
    new com.google.auth.oauth2.AccessToken(
      fakeJwt,
      new java.util.Date(System.currentTimeMillis() + 3600_000)
    )
  }
}
