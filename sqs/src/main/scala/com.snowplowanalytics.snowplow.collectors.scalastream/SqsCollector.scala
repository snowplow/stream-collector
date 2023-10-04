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
