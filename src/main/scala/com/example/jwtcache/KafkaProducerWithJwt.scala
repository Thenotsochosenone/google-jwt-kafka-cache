package com.example.jwtcache

import akka.Done
import akka.actor.typed.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream.{KillSwitches, RestartSettings, UniqueKillSwitch}
import akka.stream.scaladsl.{Keep, RestartSource, Source}
import io.prometheus.client.Counter
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

  private val messagesProduced = Counter
    .build()
    .name("kafka_messages_produced_total")
    .help("Total messages handed to the Kafka producer sink (not a confirmed-ack count)")
    .register()

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
      // Turns "retries" from "might duplicate on retry" into exactly-once
      // per partition — free correctness improvement alongside acks=all.
      .withProperty("enable.idempotence", "true")

  @volatile private var killSwitch: Option[UniqueKillSwitch] = None

  /**
   * Continuously produces messages carrying a non-recoverable fingerprint
   * of the current Google token (never the token itself, or any slice of
   * it — see tokenFingerprint below). In production the token is used
   * only for SASL/OAUTHBEARER auth and never appears in message payloads.
   *
   * Wrapped in RestartSource.onFailuresWithBackoff so a transient Kafka
   * outage (e.g. a broker restart) is retried with backoff instead of
   * permanently killing the stream.
   */
  def run(interval: FiniteDuration = 5.seconds): Future[Done] = {
    log.info("Starting Kafka producer → topic={} bootstrap={}", topic, bootstrapServers)

    val restartSettings = RestartSettings(minBackoff = 1.second, maxBackoff = 30.seconds, randomFactor = 0.2)

    val (switch, done) =
      RestartSource
        .onFailuresWithBackoff(restartSettings) { () =>
          Source
            .tick(0.seconds, interval, ())
            .mapAsync(1) { _ =>
              tokenCache.getToken.map { token =>
                val fingerprint = tokenFingerprint(token)
                val payload =
                  s"""{"ts":${System.currentTimeMillis()},"tokenFingerprint":"$fingerprint","source":"google-jwt-cache-demo"}"""
                messagesProduced.inc()
                new ProducerRecord[String, String](topic, s"key-${System.currentTimeMillis()}", payload)
              }
            }
        }
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(Producer.plainSink(producerSettings))(Keep.both)
        .run()

    killSwitch = Some(switch)
    done
  }

  /** Stops accepting new work and lets in-flight sends complete. Called from CoordinatedShutdown in Main. */
  def shutdown(): Unit = {
    log.info("Stopping Kafka producer stream")
    killSwitch.foreach(_.shutdown())
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