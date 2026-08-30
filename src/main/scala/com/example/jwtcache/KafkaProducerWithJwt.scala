package com.example.jwtcache

import akka.actor.typed.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream.scaladsl.{Sink, Source}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Demonstrates producing messages to Kafka while using a cached Google JWT
 * for SASL/OAUTHBEARER authentication.
 *
 * In real deployments you would configure the official GcpLoginCallbackHandler
 * or a custom AuthenticateCallbackHandler that reads from GoogleJwtTokenCache.
 * Here we keep the example self-contained and also emit the token into the
 * message payload so you can observe it in the consumer / dashboard.
 */
class KafkaProducerWithJwt(
    tokenCache: GoogleJwtTokenCache,
    bootstrapServers: String,
    topic: String
)(implicit system: ActorSystem[_], ec: ExecutionContext) {

  private val log = LoggerFactory.getLogger(getClass)

  private val producerSettings: ProducerSettings[String, String] =
    ProducerSettings(system, new StringSerializer, new StringSerializer)
      .withBootstrapServers(bootstrapServers)
      // For real Google Managed Kafka you would add:
      // .withProperty("security.protocol", "SASL_SSL")
      // .withProperty("sasl.mechanism", "OAUTHBEARER")
      // .withProperty("sasl.login.callback.handler.class", "com.google.cloud.hosted.kafka.auth.GcpLoginCallbackHandler")
      // .withProperty("sasl.jaas.config", "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;")
      .withProperty("acks", "all")
      .withProperty("retries", "3")

  /**
   * Continuously produces messages that contain the current Google token
   * (truncated for demo). In production the token is used only for auth.
   */
  def run(interval: FiniteDuration = 5.seconds): Future[Unit] = {
    log.info("Starting Kafka producer → topic={} bootstrap={}", topic, bootstrapServers)

    Source
      .tick(0.seconds, interval, ())
      .mapAsync(1) { _ =>
        tokenCache.getToken.map { token =>
          val preview =
            if (token.length > 40) token.take(20) + "..." + token.takeRight(10)
            else token
          val payload =
            s"""{"ts":${System.currentTimeMillis()},"tokenPreview":"$preview","source":"google-jwt-cache-demo"}"""
          new ProducerRecord[String, String](topic, s"key-${System.currentTimeMillis()}", payload)
        }
      }
      .via(Producer.flexiFlow(producerSettings))
      .map { result =>
        log.debug("Produced offset={} partition={}", result.offset, result.partition)
        result
      }
      .runWith(Sink.ignore)
      .map(_ => ())
  }
}
