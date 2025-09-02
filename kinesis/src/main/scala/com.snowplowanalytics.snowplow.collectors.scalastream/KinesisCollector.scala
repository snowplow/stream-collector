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
package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect.{IO, Resource}
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient

import com.snowplowanalytics.snowplow.collector.core.{App, Config, Sinks, Telemetry}
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.{KinesisSink, KinesisSinkConfig}

object KinesisCollector extends App[KinesisSinkConfig](BuildInfo) {

  override def mkSinks(config: Config.Streams[KinesisSinkConfig]): Resource[IO, Sinks[IO]] =
    for {
      httpClient <- mkHttpClient
      good       <- KinesisSink.resource[IO](httpClient, config.good, config.good.config.sqsGoodBuffer)
      bad        <- KinesisSink.resource[IO](httpClient, config.bad, config.bad.config.sqsBadBuffer)
    } yield Sinks(good, bad)

  override def telemetryInfo(config: Config.Streams[KinesisSinkConfig]): IO[Telemetry.TelemetryInfo] =
    mkHttpClient.use { httpClient =>
      TelemetryUtils
        .getAccountId(httpClient, config)
        .map(id =>
          Telemetry.TelemetryInfo(
            region                 = Some(config.good.config.region),
            cloud                  = Some("AWS"),
            unhashedInstallationId = id
          )
        )
    }

  private def mkHttpClient: Resource[IO, SdkAsyncHttpClient] =
    Resource.fromAutoCloseable {
      IO.delay {
        NettyNioAsyncHttpClient.builder().build()
      }
    }
}
