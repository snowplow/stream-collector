/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
  */
package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect.{IO, Resource}
import com.snowplowanalytics.snowplow.collector.core.model.Sinks
import com.snowplowanalytics.snowplow.collector.core.{App, Config}
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.{KinesisSink, KinesisSinkConfig}
import org.slf4j.LoggerFactory

import java.util.concurrent.ScheduledThreadPoolExecutor

object KinesisCollector extends App[KinesisSinkConfig](BuildInfo) {

  private lazy val log = LoggerFactory.getLogger(getClass)

  override def mkSinks(config: Config.Streams[KinesisSinkConfig]): Resource[IO, Sinks[IO]] = {
    val threadPoolExecutor = buildExecutorService(config.sink)
    for {
      good <- KinesisSink.create[IO](
        kinesisMaxBytes = config.sink.maxBytes,
        kinesisConfig   = config.sink,
        bufferConfig    = config.buffer,
        streamName      = config.good,
        sqsBufferName   = config.sink.sqsGoodBuffer,
        threadPoolExecutor
      )
      bad <- KinesisSink.create[IO](
        kinesisMaxBytes = config.sink.maxBytes,
        kinesisConfig   = config.sink,
        bufferConfig    = config.buffer,
        streamName      = config.bad,
        sqsBufferName   = config.sink.sqsBadBuffer,
        threadPoolExecutor
      )
    } yield Sinks(good, bad)
  }

  def buildExecutorService(kc: KinesisSinkConfig): ScheduledThreadPoolExecutor = {
    log.info("Creating thread pool of size " + kc.threadPoolSize)
    new ScheduledThreadPoolExecutor(kc.threadPoolSize)
  }
}
