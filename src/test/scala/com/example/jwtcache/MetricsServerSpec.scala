package com.example.jwtcache

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
 * Tests the *real* MetricsServer routes (not a re-implemented subset).
 */
class MetricsServerSpec
    extends AnyWordSpec
    with Matchers
    with ScalatestRouteTest
    with BeforeAndAfterAll
    with ScalaFutures {

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = 10.seconds, interval = 200.millis)

  private val testKit = ActorTestKit()
  implicit val typedSystem: akka.actor.typed.ActorSystem[_] = testKit.system
  implicit val ec: ExecutionContext = typedSystem.executionContext

  private val tokenCache = new GoogleJwtTokenCache()
  private val server     = new MetricsServer(tokenCache, port = 0)
  private val route      = server.routes

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
  }

  "MetricsServer" should {

    "respond 200 on /healthz" in {
      Get("/healthz") ~> route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] shouldBe "OK"
      }
    }

    "respond 200 READY on /readyz when token cache works" in {
      Get("/readyz") ~> route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] shouldBe "READY"
      }
    }

    "expose Prometheus metrics on /metrics" in {
      Get("/metrics") ~> route ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("jvm_")
      }
    }

    "return JSON cache stats on /token-stats" in {
      // Warm the cache so stats are non-trivial
      tokenCache.getToken.futureValue
      Get("/token-stats") ~> route ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("hitCount")
        body should include("missCount")
        body should include("usingMock")
      }
    }

    "invalidate cache on POST /force-refresh" in {
      tokenCache.getToken.futureValue
      Post("/force-refresh") ~> route ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include("invalidated")
      }
      // Next get still succeeds (mock reloads)
      tokenCache.getToken.futureValue should not be empty
    }
  }
}
