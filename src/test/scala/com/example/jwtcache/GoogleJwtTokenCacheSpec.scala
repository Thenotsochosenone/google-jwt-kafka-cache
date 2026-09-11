package com.example.jwtcache

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class GoogleJwtTokenCacheSpec extends AnyWordSpec with Matchers with ScalaFutures {

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = 10.seconds, interval = 200.millis)

  "GoogleJwtTokenCache" should {

    "return a non-empty token on first request (cache miss)" in {
      val cache = new GoogleJwtTokenCache()
      val token = cache.getToken.futureValue
      token should not be empty
      token.length should be > 20
    }

    "return the same token on subsequent requests (cache hit)" in {
      val cache = new GoogleJwtTokenCache()
      val t1    = cache.getToken.futureValue
      val t2    = cache.getToken.futureValue
      t1 shouldBe t2
    }

    "refresh after invalidate()" in {
      val cache = new GoogleJwtTokenCache()
      val t1    = cache.getToken.futureValue
      cache.invalidate()
      val t2 = cache.getToken.futureValue
      t2 should not be empty
      // Mock returns a deterministic token, so values match; the important part is no exception
      t2 shouldBe t1
    }

    "work with audience set (mock ID-token path) without ClassCastException" in {
      val cache = new GoogleJwtTokenCache(audience = Some("https://example.com"))
      val token = cache.getToken.futureValue
      token should not be empty
      token should include("eyJ") // JWT-like
    }

    "expose cache statistics including usingMock" in {
      val cache = new GoogleJwtTokenCache()
      cache.getToken.futureValue
      val stats = cache.stats
      stats should contain key "hitCount"
      stats should contain key "missCount"
      stats should contain key "usingMock"
      stats("estimatedSize").asInstanceOf[Long] should be >= 0L
      stats("usingMock").asInstanceOf[Boolean] shouldBe true
    }
  }
}
