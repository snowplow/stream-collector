package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect._
import cats.effect.kernel.Resource
import com.snowplowanalytics.snowplow.collector.core.model.Sinks
import com.snowplowanalytics.snowplow.collector.core.{App, Config, Telemetry}
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.{PubSubSink, PubSubSinkConfig}

object PubSubCollector extends App[PubSubSinkConfig](BuildInfo) {

  override def mkSinks(config: Config.Streams[PubSubSinkConfig]): Resource[IO, Sinks[IO]] =
    for {
      good <- PubSubSink.create[IO](config.good)
      bad  <- PubSubSink.create[IO](config.bad)
    } yield Sinks(good, bad)

  override def telemetryInfo(config: Config.Streams[PubSubSinkConfig]): IO[Telemetry.TelemetryInfo] =
    IO(
      Telemetry.TelemetryInfo(
        region                 = None,
        cloud                  = Some("GCP"),
        unhashedInstallationId = Some(config.good.config.googleProjectId)
      )
    )
}
