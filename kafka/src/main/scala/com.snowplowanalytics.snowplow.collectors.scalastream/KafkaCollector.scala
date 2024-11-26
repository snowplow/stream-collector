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
import com.snowplowanalytics.snowplow.collector.core.model.Sinks
import com.snowplowanalytics.snowplow.collector.core.{App, Config, Telemetry}
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks._

object KafkaCollector extends App[KafkaSinkConfig](BuildInfo) {

  override def mkSinks(config: Config.Streams[KafkaSinkConfig]): Resource[IO, Sinks[IO]] =
    for {
      good <- KafkaSink.create[IO](
        config.good,
        classOf[GoodAzureAuthenticationCallbackHandler].getName
      )
      bad <- KafkaSink.create[IO](
        config.bad,
        classOf[BadAzureAuthenticationCallbackHandler].getName
      )
    } yield Sinks(good, bad)

  override def telemetryInfo(config: Config.Streams[KafkaSinkConfig]): IO[Telemetry.TelemetryInfo] =
    TelemetryUtils.getAzureSubscriptionId.map {
      case None     => Telemetry.TelemetryInfo(None, None, None)
      case Some(id) => Telemetry.TelemetryInfo(None, Some("Azure"), Some(id))
    }

}
