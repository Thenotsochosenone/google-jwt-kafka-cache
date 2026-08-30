package com.example.jwtcache

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.ExecutionContext

class MetricsServerSpec
    extends AnyWordSpec
    with Matchers
    with ScalatestRouteTest
    with BeforeAndAfterAll {

  private val testKit = ActorTestKit()
  implicit val typedSystem: akka.actor.typed.ActorSystem[_] = testKit.system
  implicit val ec: ExecutionContext = typedSystem.executionContext

  private val tokenCache = new GoogleJwtTokenCache()

  // Minimal route under test (mirrors MetricsServer endpoints)
  private def route = {
    import akka.http.scaladsl.server.Directives._
    import akka.http.scaladsl.model._
    import io.prometheus.client.CollectorRegistry
    import io.prometheus.client.exporter.common.TextFormat
    import java.io.StringWriter

    path("healthz") {
      get { complete(StatusCodes.OK -> "OK") }
    } ~
      path("metrics") {
        get {
          complete {
            val writer = new StringWriter()
            TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples())
            HttpEntity(ContentTypes.`text/plain(UTF-8)`, writer.toString)
          }
        }
      }
  }

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

    "expose Prometheus metrics" in {
      Get("/metrics") ~> route ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("jvm_")
      }
    }
  }
}
