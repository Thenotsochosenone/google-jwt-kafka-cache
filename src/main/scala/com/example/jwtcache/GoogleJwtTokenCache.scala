package com.example.jwtcache

import com.github.benmanes.caffeine.cache.{Caffeine, LoadingCache}
import com.google.auth.oauth2.{GoogleCredentials, IdTokenCredentials, IdTokenProvider}
import io.prometheus.client.{Counter, Gauge, Histogram}
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeUnit
import scala.concurrent.{blocking, ExecutionContext, Future}
import scala.util.Try

/**
 * Thread-safe, observable cache for Google access / ID tokens.
 *
 * - Uses Caffeine for automatic expiry & refresh
 * - Respects real token expiry (with skew) in addition to Caffeine TTL
 * - Exposes Prometheus metrics with correct hit/miss/error labeling
 * - Supports both Access Tokens and ID Tokens (JWT)
 * - Falls back to mock credentials when ADC is unavailable (local/dev)
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
    .help("Total token requests (cache hit + miss + error)")
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

  private val usingMock: Boolean = credentials.isInstanceOf[MockGoogleCredentials]

  // ---------- Caffeine cache (TTL is a safety net; real expiry is checked via expiresAtMs) ----------
  private val cache: LoadingCache[String, CachedToken] = Caffeine
    .newBuilder()
    .expireAfterWrite(cacheTtlMinutes, TimeUnit.MINUTES)
    .maximumSize(10)
    .recordStats()
    .build((_: String) => fetchFreshToken())

  private case class CachedToken(value: String, expiresAtMs: Long) {
    def isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
  }

  private def fetchFreshToken(): CachedToken = {
    val timer = tokenRefreshDuration.startTimer()
    try {
      log.info(
        "Refreshing Google token (audience={}, mock={})",
        audience.getOrElse("access-token"),
        usingMock
      )

      // These are synchronous network calls to Google's token endpoint.
      // `blocking` tells the dispatcher's fork-join pool to compensate
      // by spinning up an extra thread while this one is parked.
      val (tokenValue, expiresInMs) = blocking {
        credentials.refreshIfExpired()

        (audience, usingMock) match {
          // Mock path for ID tokens – avoids ClassCastException on IdTokenProvider
          case (Some(_), true) =>
            val fakeJwt = MockGoogleCredentials.fakeJwt
            (fakeJwt, 3600_000L)

          // Real ID token path
          case (Some(aud), false) =>
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

          // Access token (real or mock)
          case (None, _) =>
            val token = credentials.getAccessToken
            (
              token.getTokenValue,
              Option(token.getExpirationTime)
                .map(_.getTime - System.currentTimeMillis())
                .getOrElse(3600_000L)
            )
        }
      }

      lastRefreshTimestamp.set(System.currentTimeMillis() / 1000.0)
      cacheSize.set(1)
      tokenRequests.labels("miss").inc()

      log.info("Token refreshed successfully, expires in ~{}s", expiresInMs / 1000)
      // Store absolute expiry with skew so we refresh slightly before Google expires the token
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

  /**
   * Non-blocking get – returns a valid cached token or triggers a refresh.
   * Correctly labels Prometheus metrics as hit vs miss.
   * Invalidates and reloads if the stored token has passed its (skewed) expiry.
   */
  def getToken: Future[String] = Future {
    val key = "google-token"

    Option(cache.getIfPresent(key)) match {
      case Some(cached) if !cached.isExpired() =>
        tokenRequests.labels("hit").inc()
        cacheSize.set(cache.estimatedSize())
        cached.value

      case Some(_) =>
        // Present but past skewed expiry – force reload
        log.debug("Cached token past skewed expiry – forcing refresh")
        cache.invalidate(key)
        val fresh = cache.get(key) // loader increments "miss"
        cacheSize.set(cache.estimatedSize())
        fresh.value

      case None =>
        val fresh = cache.get(key) // loader increments "miss"
        cacheSize.set(cache.estimatedSize())
        fresh.value
    }
  }

  /** Force invalidation (e.g. after receiving 401 from a downstream service) */
  def invalidate(): Unit = {
    log.info("Invalidating token cache")
    cache.invalidateAll()
    cacheSize.set(0)
  }

  /** Current cache stats for the /token-stats endpoint */
  def stats: Map[String, Any] = {
    val s = cache.stats()
    Map(
      "hitCount"      -> s.hitCount(),
      "missCount"     -> s.missCount(),
      "loadSuccess"   -> s.loadSuccessCount(),
      "loadFailure"   -> s.loadFailureCount(),
      "evictionCount" -> s.evictionCount(),
      "estimatedSize" -> cache.estimatedSize(),
      "usingMock"     -> usingMock
    )
  }
}

/**
 * Minimal mock credentials used when running locally without GCP ADC.
 * Returns a deterministic fake JWT so the pipeline can be exercised without a GCP project.
 * Access-token and ID-token demo paths both work with this mock.
 */
class MockGoogleCredentials extends GoogleCredentials {
  override def refreshAccessToken(): com.google.auth.oauth2.AccessToken =
    new com.google.auth.oauth2.AccessToken(
      MockGoogleCredentials.fakeJwt,
      new java.util.Date(System.currentTimeMillis() + 3600_000)
    )
}

object MockGoogleCredentials {
  val fakeJwt: String =
    "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9." +
      "eyJpc3MiOiJodHRwczovL2FjY291bnRzLmdvb2dsZS5jb20iLCJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6Ik1vY2sgVXNlciIsImlhdCI6MTUxNjIzOTAyMiwiZXhwIjo5OTk5OTk5OTk5fQ." +
      "mock-signature"
}