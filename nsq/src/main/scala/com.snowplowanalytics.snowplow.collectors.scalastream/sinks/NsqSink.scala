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

import com.snowplowanalytics.snowplow.streams.nsq.{BackoffPolicy, NsqFactory, NsqSinkConfigM => CommonNsqSinkConfigM}
import com.snowplowanalytics.snowplow.collector.core.{Config, Sink}

import scala.concurrent.duration.{Duration, DurationInt}

object NsqSink {

  implicit private def logger[F[_]: Sync]: Logger[F] = Slf4jLogger.getLogger[F]

  def create[F[_]: Async](
    nsqConfig: Config.Sink[NsqSinkConfig],
    factory: NsqFactory[F]
  ): Resource[F, Sink[F]] =
    for {
      commonSink <- factory.sink(convertConfig(nsqConfig.name, nsqConfig.config))
      sink <- Sink.ofCommonStreamsSink(
        name                       = nsqConfig.name,
        maxBytes                   = nsqConfig.config.maxBytes,
        sinkRetryInterval          = Duration.Zero,
        startupHealthCheckInterval = Duration.Zero,
        sink                       = commonSink
      )
    } yield sink

  /** Converts from the collector's format of sink config into common-streams's format of sink config
    *
    *  Note that `byteLimit` is set to `Int.MaxValue`. This is safe because the collector already
    *  takes responsibility for batching up events to not exceed the batch limit.  We set it to
    *  `Int.MaxValue` to make it clear which component has responsibility for batching.
    */
  private def convertConfig(name: String, c: NsqSinkConfig): CommonNsqSinkConfigM[Id] =
    CommonNsqSinkConfigM[Id](
      topic         = name,
      nsqdHost      = c.host,
      nsqdPort      = c.port,
      byteLimit     = Int.MaxValue,
      backoffPolicy = BackoffPolicy(minBackoff = 100.millis, maxBackoff = 1.second, maxRetries = Some(3))
    )
}
