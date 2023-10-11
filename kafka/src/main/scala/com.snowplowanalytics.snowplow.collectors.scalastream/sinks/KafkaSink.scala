/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
  */
package com.snowplowanalytics.snowplow.collectors.scalastream
package sinks

import cats.effect.{Resource, Sync}

import org.slf4j.LoggerFactory

import java.util.Properties

import org.apache.kafka.clients.producer._

import com.snowplowanalytics.snowplow.collector.core.{Config, Sink}

/**
  * Kafka Sink for the Scala Stream Collector
  */
class KafkaSink[F[_]: Sync](
  val maxBytes: Int,
  kafkaProducer: KafkaProducer[String, Array[Byte]],
  topicName: String
) extends Sink[F] {

  private lazy val log                        = LoggerFactory.getLogger(getClass())
  @volatile private var kafkaHealthy: Boolean = false
  override def isHealthy: F[Boolean]          = Sync[F].pure(kafkaHealthy)

  /**
    * Store raw events to the topic
    *
    * @param events The list of events to send
    * @param key The partition key to use
    */
  override def storeRawEvents(events: List[Array[Byte]], key: String): F[Unit] = Sync[F].delay {
    log.debug(s"Writing ${events.size} Thrift records to Kafka topic $topicName at key $key")
    events.foreach { event =>
      kafkaProducer.send(
        new ProducerRecord(topicName, key, event),
        new Callback {
          override def onCompletion(metadata: RecordMetadata, e: Exception): Unit =
            if (e != null) {
              kafkaHealthy = false
              log.error(s"Sending event failed: ${e.getMessage}")
            } else {
              kafkaHealthy = true
            }
        }
      )
    }
  }
}

object KafkaSink {

  def create[F[_]: Sync](
    maxBytes: Int,
    topicName: String,
    kafkaConfig: KafkaSinkConfig,
    bufferConfig: Config.Buffer
  ): Resource[F, KafkaSink[F]] =
    for {
      kafkaProducer <- createProducer(kafkaConfig, bufferConfig)
      kafkaSink = new KafkaSink(maxBytes, kafkaProducer, topicName)
    } yield kafkaSink

  /**
    * Creates a new Kafka Producer with the given
    * configuration options
    *
    * @return a new Kafka Producer
    */
  private def createProducer[F[_]: Sync](
    kafkaConfig: KafkaSinkConfig,
    bufferConfig: Config.Buffer
  ): Resource[F, KafkaProducer[String, Array[Byte]]] = {
    val acquire = Sync[F].delay {
      val props = new Properties()
      props.setProperty("bootstrap.servers", kafkaConfig.brokers)
      props.setProperty("acks", "all")
      props.setProperty("retries", kafkaConfig.retries.toString)
      props.setProperty("buffer.memory", bufferConfig.byteLimit.toString)
      props.setProperty("linger.ms", bufferConfig.timeLimit.toString)
      props.setProperty("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
      props.setProperty("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer")

      // Can't use `putAll` in JDK 11 because of https://github.com/scala/bug/issues/10418
      kafkaConfig.producerConf.getOrElse(Map()).foreach { case (k, v) => props.setProperty(k, v) }

      new KafkaProducer[String, Array[Byte]](props)
    }
    val release = (p: KafkaProducer[String, Array[Byte]]) => Sync[F].delay(p.close())
    Resource.make(acquire)(release)
  }
}
