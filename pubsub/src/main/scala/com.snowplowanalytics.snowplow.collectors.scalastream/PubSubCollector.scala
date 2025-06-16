package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect.{IO, Resource}
import com.snowplowanalytics.snowplow.streams.pubsub.{PubsubFactory, PubsubFactoryConfig}
import com.snowplowanalytics.snowplow.collector.core.{App, Config, Sinks, Telemetry}
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.{PubSubSink, PubSubSinkConfig}

object PubSubCollector extends App[PubSubSinkConfig](BuildInfo) {

  override def mkSinks(config: Config.Streams[PubSubSinkConfig]): Resource[IO, Sinks[IO]] =
    for {
      factory <- PubsubFactory.resource[IO](
        PubsubFactoryConfig(config.good.config.gcpUserAgent, config.good.config.emulatorHost)
      )
      good <- PubSubSink.create[IO](config.good, factory)
      bad  <- PubSubSink.create[IO](config.bad, factory)
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
