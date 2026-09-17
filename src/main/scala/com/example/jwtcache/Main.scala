package com.example.jwtcache

import akka.actor.CoordinatedShutdown
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorSystem, DispatcherSelector}
import akka.Done
import com.typesafe.config.ConfigFactory
import io.prometheus.client.hotspot.DefaultExports
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal

object Main {

  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    // Previously `object Main extends App`: DelayedInit means a
    // synchronous exception during field initialization happened OUTSIDE
    // the old try/catch. An explicit main() wraps the entire startup path.
    try {
      run()
    } catch {
      case NonFatal(ex) =>
        log.error("Fatal error during startup", ex)
        sys.exit(1)
    }
  }

  private def run(): Unit = {
    // Enable JVM metrics (GC, threads, memory …)
    DefaultExports.initialize()

    val config = ConfigFactory.load()

    implicit val system: ActorSystem[Nothing] =
      ActorSystem(Behaviors.empty, "google-jwt-kafka-cache")
    implicit val ec: ExecutionContext = system.executionContext

    // Dedicated dispatcher for GoogleJwtTokenCache's blocking calls to
    // Google's token endpoint, so a slow/hanging call can't starve the
    // dispatcher backing Akka HTTP routes and the Kafka streams.
    // Defined in application.conf as "blocking-io-dispatcher".
    val blockingEc: ExecutionContext =
      system.dispatchers.lookup(DispatcherSelector.fromConfig("blocking-io-dispatcher"))

    val bootstrapServers = config.getString("app.kafka.bootstrap-servers")
    val topic            = config.getString("app.kafka.topic")
    val metricsPort      = config.getInt("app.metrics.port")
    val produceInterval  = config.getDuration("app.producer.interval").toMillis.millis
    val audience         = if (config.hasPath("app.google.audience")) Some(config.getString("app.google.audience")) else None

    log.info(
      """
        |=======================================================
        |  Google JWT Kafka Cache Demo
        |  bootstrap = {}
        |  topic     = {}
        |  metrics   = http://0.0.0.0:{}/metrics
        |=======================================================
        |""".stripMargin,
      bootstrapServers,
      topic,
      metricsPort
    )

    // Pass the dedicated blocking dispatcher explicitly
    val tokenCache = new GoogleJwtTokenCache(audience = audience)(blockingEc)

    val metricsServer = new MetricsServer(tokenCache, port = metricsPort)
    val producer      = new KafkaProducerWithJwt(tokenCache, bootstrapServers, topic)
    val consumer      = new KafkaConsumerWithJwt(bootstrapServers, topic)

    // Start everything. Failures are logged and cause process exit.
    metricsServer.start().failed.foreach { ex =>
      log.error("Metrics server failed to bind — exiting", ex)
      sys.exit(1)
    }
    producer.run(produceInterval).failed.foreach { ex =>
      log.error("Producer stream terminated unexpectedly — exiting", ex)
      sys.exit(1)
    }
    consumer.run().failed.foreach { ex =>
      log.error("Consumer stream terminated unexpectedly — exiting", ex)
      sys.exit(1)
    }

    // Ordered shutdown: stop producer/consumer streams before the
    // actor system materializer goes away.
    val coordinatedShutdown = CoordinatedShutdown(system.toClassic)
    coordinatedShutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone, "stop-kafka-producer") { () =>
      Future {
        producer.shutdown()
        Done
      }
    }
    coordinatedShutdown.addTask(CoordinatedShutdown.PhaseServiceRequestsDone, "stop-kafka-consumer") { () =>
      Future {
        consumer.shutdown()
        Done
      }
    }

    Await.result(system.whenTerminated, Duration.Inf)
  }
}