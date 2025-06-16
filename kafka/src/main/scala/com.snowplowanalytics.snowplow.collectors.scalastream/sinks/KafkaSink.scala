/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This software is made available by Snowplow Analytics, Ltd.,
  * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
  * located at https://docs.snowplow.io/limited-use-license-1.1
  * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
  * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
  */
package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import cats.Id
import cats.effect.{Async, Resource, Sync}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import com.snowplowanalytics.snowplow.streams.kafka.{KafkaFactory, KafkaSinkConfigM => CommonKafkaSinkConfigM}
import com.snowplowanalytics.snowplow.collector.core.{Config, Sink}

object KafkaSink {

  implicit private def logger[F[_]: Sync]: Logger[F] = Slf4jLogger.getLogger[F]

  def create[F[_]: Async](
    kafkaConfig: Config.Sink[KafkaSinkConfig],
    factory: KafkaFactory[F]
  ): Resource[F, Sink[F]] =
    for {
      commonSink <- factory.sink(convertConfig(kafkaConfig.name, kafkaConfig.config))
      sink <- Sink.ofCommonStreamsSink(
        name                       = kafkaConfig.name,
        maxBytes                   = kafkaConfig.config.maxBytes,
        sinkRetryInterval          = kafkaConfig.config.retryInterval,
        startupHealthCheckInterval = kafkaConfig.config.startupCheckInterval,
        sink                       = commonSink
      )
    } yield sink

  private def convertConfig(name: String, c: KafkaSinkConfig): CommonKafkaSinkConfigM[Id] =
    CommonKafkaSinkConfigM[Id](
      topicName        = name,
      bootstrapServers = c.brokers,
      producerConf     = c.producerConf
    )
}
