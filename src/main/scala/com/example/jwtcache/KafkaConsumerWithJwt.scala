package com.example.jwtcache

import akka.Done
import akka.actor.typed.ActorSystem
import akka.kafka.scaladsl.{Consumer, Committer}
import akka.kafka.{CommitterSettings, ConsumerSettings, Subscriptions}
import akka.stream.{KillSwitches, RestartSettings, UniqueKillSwitch}
import akka.stream.scaladsl.{Keep, RestartSource, Sink}
import io.prometheus.client.Counter
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Simple consumer that logs messages and demonstrates that the pipeline
 * works end-to-end. In a real system the same token cache would be used
 * for the consumer's OAUTHBEARER authentication.
 */
class KafkaConsumerWithJwt(
    bootstrapServers: String,
    topic: String,
    groupId: String = "google-jwt-cache-demo"
)(implicit system: ActorSystem[_], ec: ExecutionContext) {

  private val log = LoggerFactory.getLogger(getClass)

  private val messagesConsumed = Counter
    .build()
    .name("kafka_messages_consumed_total")
    .help("Total messages consumed and successfully committed")
    .register()

  private val consumerSettings: ConsumerSettings[String, String] =
    ConsumerSettings(system, new StringDeserializer, new StringDeserializer)
      .withBootstrapServers(bootstrapServers)
      .withGroupId(groupId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")

  @volatile private var killSwitch: Option[UniqueKillSwitch] = None

  /**
   * Wrapped in RestartSource.onFailuresWithBackoff so a transient Kafka
   * outage retries with backoff instead of permanently killing the consumer.
   */
  def run(): Future[Done] = {
    log.info("Starting Kafka consumer ← topic={} group={}", topic, groupId)

    val committerSettings = CommitterSettings(system)
    val restartSettings = RestartSettings(minBackoff = 1.second, maxBackoff = 30.seconds, randomFactor = 0.2)

    val (switch, done) =
      RestartSource
        .onFailuresWithBackoff(restartSettings) { () =>
          Consumer
            .committableSource(consumerSettings, Subscriptions.topics(topic))
            .map { msg =>
              // Keep the default (INFO) log line free of message bodies — only
              // partition/offset/key. Full payload is still available at DEBUG.
              log.info(
                "Consumed partition={} offset={} key={}",
                msg.record.partition,
                msg.record.offset,
                msg.record.key
              )
              log.debug("Message value={}", msg.record.value)
              messagesConsumed.inc()
              msg.committableOffset
            }
            .via(Committer.flow(committerSettings))
        }
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(Sink.ignore)(Keep.both)
        .run()

    killSwitch = Some(switch)
    done
  }

  /** Stops accepting new work and lets any in-flight commit complete. Called from CoordinatedShutdown in Main. */
  def shutdown(): Unit = {
    log.info("Stopping Kafka consumer stream")
    killSwitch.foreach(_.shutdown())
  }
}