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
import com.snowplowanalytics.snowplow.collector.core.{App, Config, Telemetry}
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks._

object NsqCollector extends App[NsqSinkConfig](BuildInfo) {
  override def mkSinks(config: Config.Streams[NsqSinkConfig]): Resource[IO, Sinks[IO]] =
    for {
      good <- NsqSink.create[IO](
        config.sink.maxBytes,
        config.sink,
        config.good
      )
      bad <- NsqSink.create[IO](
        config.sink.maxBytes,
        config.sink,
        config.bad
      )
    } yield Sinks(good, bad)

  override def telemetryInfo(config: Config[NsqSinkConfig]): Telemetry.TelemetryInfo =
    Telemetry.TelemetryInfo(None, None)
}
