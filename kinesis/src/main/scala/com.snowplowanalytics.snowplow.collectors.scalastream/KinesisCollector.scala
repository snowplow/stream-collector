/*
 * Copyright (c) 2013-2022 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, and
 * you may not use this file except in compliance with the Apache License
 * Version 2.0.  You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Apache License Version 2.0 is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect.{IO, Resource}

import com.snowplowanalytics.snowplow.collector.core.model.Sinks
import com.snowplowanalytics.snowplow.collector.core.{App, Config, Telemetry}
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

  override def telemetryInfo(config: Config.Streams[KinesisSinkConfig]): IO[Telemetry.TelemetryInfo] =
    TelemetryUtils
      .getAccountId(config)
      .map(id =>
        Telemetry.TelemetryInfo(
          region                 = Some(config.sink.region),
          cloud                  = Some("AWS"),
          unhashedInstallationId = id
        )
      )

  def buildExecutorService(kc: KinesisSinkConfig): ScheduledThreadPoolExecutor = {
    log.info("Creating thread pool of size " + kc.threadPoolSize)
    new ScheduledThreadPoolExecutor(kc.threadPoolSize)
  }
}
