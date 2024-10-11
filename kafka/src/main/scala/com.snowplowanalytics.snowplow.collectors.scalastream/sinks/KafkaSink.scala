/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This software is made available by Snowplow Analytics, Ltd.,
  * under the terms of the Snowplow Limited Use License Agreement, Version 1.0
  * located at https://docs.snowplow.io/limited-use-license-1.0
  * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
  * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
  */
package com.snowplowanalytics.snowplow.collectors.scalastream
package sinks

import cats.implicits._
import cats.effect._

import org.slf4j.LoggerFactory

import fs2.kafka._

import com.snowplowanalytics.snowplow.collector.core.{Config, Sink}

/**
  * Kafka Sink for the Scala Stream Collector
  */
class KafkaSink[F[_]: Async](
  val maxBytes: Int,
  isHealthyState: Ref[F, Boolean],
  kafkaProducer: KafkaProducer[F, String, Array[Byte]],
  topicName: String
) extends Sink[F] {

  private lazy val log = LoggerFactory.getLogger(getClass())

  override def isHealthy: F[Boolean] = isHealthyState.get

  /**
    * Store raw events to the topic
    *
    * @param events The list of events to send
    * @param key The partition key to use
    */
  override def storeRawEvents(events: List[Array[Byte]], key: String): F[Unit] = {
    log.debug(s"Writing ${events.size} Thrift records to Kafka topic $topicName at key $key")
    val records = ProducerRecords(events.map(e => (ProducerRecord(topicName, key, e))))
    kafkaProducer.produce(records).onError { case _: Throwable => isHealthyState.set(false) } *> isHealthyState.set(
      true
    )
  }
}

object KafkaSink {

  def create[F[_]: Async](
    sinkConfig: Config.Sink[KafkaSinkConfig],
    authCallbackClass: String
  ): Resource[F, KafkaSink[F]] =
    for {
      isHealthyState <- Resource.eval(Ref.of[F, Boolean](false))
      kafkaProducer  <- createProducer(sinkConfig.config, sinkConfig.buffer, authCallbackClass)
      kafkaSink = new KafkaSink(sinkConfig.config.maxBytes, isHealthyState, kafkaProducer, sinkConfig.name)
    } yield kafkaSink

  /**
    * Creates a new Kafka Producer with the given
    * configuration options
    *
    * @return a new Kafka Producer
    */
  private def createProducer[F[_]: Async](
    kafkaConfig: KafkaSinkConfig,
    bufferConfig: Config.Buffer,
    authCallbackClass: String
  ): Resource[F, KafkaProducer[F, String, Array[Byte]]] = {
    val props = Map(
      "acks"                              -> "all",
      "retries"                           -> kafkaConfig.retries.toString,
      "buffer.memory"                     -> bufferConfig.byteLimit.toString,
      "linger.ms"                         -> bufferConfig.timeLimit.toString,
      "max.request.size"                  -> kafkaConfig.maxBytes.toString,
      "key.serializer"                    -> "org.apache.kafka.common.serialization.StringSerializer",
      "value.serializer"                  -> "org.apache.kafka.common.serialization.ByteArraySerializer",
      "sasl.login.callback.handler.class" -> authCallbackClass
    ) ++ kafkaConfig.producerConf.getOrElse(Map.empty)

    val producerSettings =
      ProducerSettings[F, String, Array[Byte]].withBootstrapServers(kafkaConfig.brokers).withProperties(props)

    KafkaProducer.resource(producerSettings)
  }
}
