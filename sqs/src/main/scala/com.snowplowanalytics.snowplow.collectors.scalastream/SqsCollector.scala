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

import java.util.concurrent.ScheduledThreadPoolExecutor
import cats.effect.{IO, Resource}
import com.snowplowanalytics.snowplow.collector.core.model.Sinks
import com.snowplowanalytics.snowplow.collector.core.{App, Config, Telemetry}
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

  override def telemetryInfo(config: Config.Streams[SqsSinkConfig]): IO[Telemetry.TelemetryInfo] =
    TelemetryUtils
      .getAccountId(config)
      .map(id =>
        Telemetry.TelemetryInfo(
          region                 = Some(config.sink.region),
          cloud                  = Some("AWS"),
          unhashedInstallationId = id
        )
      )
}
