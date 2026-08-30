package com.example.jwtcache

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import com.typesafe.config.ConfigFactory
import io.prometheus.client.hotspot.DefaultExports
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.control.NonFatal

object Main extends App {

  private val log = LoggerFactory.getLogger(getClass)

  // Enable JVM metrics (GC, threads, memory …)
  DefaultExports.initialize()

  val config = ConfigFactory.load()

  implicit val system: ActorSystem[Nothing] =
    ActorSystem(Behaviors.empty, "google-jwt-kafka-cache")
  implicit val ec: ExecutionContext = system.executionContext

  val bootstrapServers = config.getString("app.kafka.bootstrap-servers")
  val topic            = config.getString("app.kafka.topic")
  val metricsPort      = config.getInt("app.metrics.port")
  val produceInterval  = config.getDuration("app.producer.interval").toMillis.millis
  val audience =
    if (config.hasPath("app.google.audience")) Some(config.getString("app.google.audience"))
    else None

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

  val tokenCache = new GoogleJwtTokenCache(audience = audience)

  val metricsServer = new MetricsServer(tokenCache, port = metricsPort)
  val producer      = new KafkaProducerWithJwt(tokenCache, bootstrapServers, topic)
  val consumer      = new KafkaConsumerWithJwt(bootstrapServers, topic)

  // Start everything
  metricsServer.start()
  producer.run(produceInterval)
  consumer.run()

  // Keep the JVM alive
  sys.addShutdownHook {
    log.info("Shutting down…")
    system.terminate()
    Await.result(system.whenTerminated, 10.seconds)
  }

  try {
    Await.result(system.whenTerminated, Duration.Inf)
  } catch {
    case NonFatal(ex) =>
      log.error("Application terminated with error", ex)
      sys.exit(1)
  }
}
