package com.example.jwtcache

import akka.actor.typed.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream.scaladsl.Source
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Demonstrates producing messages to Kafka while using a cached Google JWT
 * for SASL/OAUTHBEARER authentication.
 *
 * In real deployments you would configure the official GcpLoginCallbackHandler
 * or a custom AuthenticateCallbackHandler that reads from GoogleJwtTokenCache.
 * Here we keep the example self-contained; each message carries a SHA-256
 * fingerprint of the current token (not the token, and not a slice of it)
 * so you can observe rotation in the consumer / dashboard without ever
 * putting recoverable credential material on the wire.
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
   * Continuously produces messages carrying a non-recoverable fingerprint
   * of the current Google token (never the token itself, or any slice of
   * it — see tokenFingerprint below). In production the token is used
   * only for SASL/OAUTHBEARER auth and never appears in message payloads.
   */
  def run(interval: FiniteDuration = 5.seconds): Future[Unit] = {
    log.info("Starting Kafka producer → topic={} bootstrap={}", topic, bootstrapServers)

    Source
      .tick(0.seconds, interval, ())
      .mapAsync(1) { _ =>
        tokenCache.getToken.map { token =>
          val fingerprint = tokenFingerprint(token)
          val payload =
            s"""{"ts":${System.currentTimeMillis()},"tokenFingerprint":"$fingerprint","source":"google-jwt-cache-demo"}"""
          new ProducerRecord[String, String](topic, s"key-${System.currentTimeMillis()}", payload)
        }
      }
      .runWith(Producer.plainSink(producerSettings))
      .map(_ => ())
  }

  /**
   * A short, one-way SHA-256 fingerprint of the token. Lets you visually
   * confirm rotation (the value changes when the underlying token
   * refreshes) without exposing any bytes an attacker could use to
   * reconstruct or correlate the real credential.
   */
  private def tokenFingerprint(token: String): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))
    digest.take(4).map(b => f"$b%02x").mkString
  }
}
