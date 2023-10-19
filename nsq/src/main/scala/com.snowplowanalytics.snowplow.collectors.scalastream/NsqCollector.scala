/*
 * Copyright (c) 2013-2023 Snowplow Analytics Ltd. All rights reserved.
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
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks._

object NsqCollector extends App[NsqSinkConfig](BuildInfo) {
  override def mkSinks(config: Config.Streams[NsqSinkConfig]): Resource[IO, Sinks[IO]] =
    for {
      good <- NsqSink.create[IO](config.good)
      bad  <- NsqSink.create[IO](config.bad)
    } yield Sinks(good, bad)

  override def telemetryInfo(config: Config.Streams[NsqSinkConfig]): IO[Telemetry.TelemetryInfo] =
    IO(Telemetry.TelemetryInfo(None, None, None))
}
