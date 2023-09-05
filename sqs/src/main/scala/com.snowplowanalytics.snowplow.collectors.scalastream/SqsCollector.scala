/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
  */
package com.snowplowanalytics.snowplow.collectors.scalastream

import java.util.concurrent.ScheduledThreadPoolExecutor

import cats.effect.{IO, Resource}

import com.snowplowanalytics.snowplow.collector.core.model.Sinks
import com.snowplowanalytics.snowplow.collector.core.{App, Config}
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks._

object SqsCollector extends App[SqsSinkConfig](BuildInfo) {

  override def mkSinks(config: Config.Streams[SqsSinkConfig]): Resource[IO, Sinks[IO]] = {
    val threadPoolExecutor = new ScheduledThreadPoolExecutor(config.sink.threadPoolSize)
    for {
      good <- SqsSink.create[IO](
        config.sink.maxBytes,
        config.sink,
        config.buffer,
        config.good,
        threadPoolExecutor
      )
      bad <- SqsSink.create[IO](
        config.sink.maxBytes,
        config.sink,
        config.buffer,
        config.bad,
        threadPoolExecutor
      )
    } yield Sinks(good, bad)
  }
}
