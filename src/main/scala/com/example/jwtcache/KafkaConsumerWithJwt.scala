package com.example.jwtcache

import akka.actor.typed.ActorSystem
import akka.kafka.scaladsl.{Committer, Consumer}
import akka.kafka.{CommitterSettings, ConsumerSettings, Subscriptions}
import akka.stream.scaladsl.Sink
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory

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

  private val consumerSettings: ConsumerSettings[String, String] =
    ConsumerSettings(system, new StringDeserializer, new StringDeserializer)
      .withBootstrapServers(bootstrapServers)
      .withGroupId(groupId)
      .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")

  def run(): Future[Unit] = {
    log.info("Starting Kafka consumer ← topic={} group={}", topic, groupId)

    val committerSettings = CommitterSettings(system)

    Consumer
      .committableSource(consumerSettings, Subscriptions.topics(topic))
      .map { msg =>
        log.info(
          "Consumed partition={} offset={} key={} value={}",
          msg.record.partition,
          msg.record.offset,
          msg.record.key,
          msg.record.value
        )
        msg.committableOffset
      }
      .via(Committer.flow(committerSettings))
      .runWith(Sink.ignore)
      .map(_ => ())
  }
}
