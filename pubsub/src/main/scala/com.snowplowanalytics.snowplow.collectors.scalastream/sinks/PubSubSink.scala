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

import com.snowplowanalytics.snowplow.streams.pubsub.{
  PubsubFactory,
  PubsubSinkConfigM => CommonPubsubSinkConfigM,
  PubsubSinkConfig => CommonPubsubSinkConfig
}
import com.snowplowanalytics.snowplow.collector.core.{Config, Sink}

object PubSubSink {

  implicit private def logger[F[_]: Sync]: Logger[F] = Slf4jLogger.getLogger[F]

  def create[F[_]: Async](
    pubsubConfig: Config.Sink[PubSubSinkConfig],
    factory: PubsubFactory[F]
  ): Resource[F, Sink[F]] =
    for {
      commonSink <- factory.sink(convertConfig(pubsubConfig.name, pubsubConfig.config))
      sink <- Sink.ofCommonStreamsSink(
        name                       = pubsubConfig.name,
        maxBytes                   = pubsubConfig.config.maxBytes,
        sinkRetryInterval          = pubsubConfig.config.retryInterval,
        startupHealthCheckInterval = pubsubConfig.config.startupCheckInterval,
        sink                       = commonSink
      )
    } yield sink

  /** Converts from the collector's format of sink config into common-streams's format of sink config
    *
    *  Note that `batchSize` is set to `Int.MaxValue`. This is safe because the collector already
    *  takes responsibility for batching up events to not exceed the batch limit.  We set it to
    *  `Int.MaxValue` to make it clear which component has responsibility for batching.
    */
  private def convertConfig(name: String, c: PubSubSinkConfig): CommonPubsubSinkConfigM[Id] =
    CommonPubsubSinkConfigM[Id](
      topic                = CommonPubsubSinkConfig.Topic(c.googleProjectId, name),
      batchSize            = Int.MaxValue,
      requestByteThreshold = Int.MaxValue
    )
}
