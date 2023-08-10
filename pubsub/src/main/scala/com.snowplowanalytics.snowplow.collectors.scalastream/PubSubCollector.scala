package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect._
import cats.effect.kernel.Resource
import com.snowplowanalytics.snowplow.collector.core.model.Sinks
import com.snowplowanalytics.snowplow.collector.core.{App, Config}
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.{PubSubSink, PubSubSinkConfig}

object PubSubCollector extends App[PubSubSinkConfig](BuildInfo) {

  override def mkSinks(config: Config.Streams[PubSubSinkConfig]): Resource[IO, Sinks[IO]] =
    for {
      good <- PubSubSink.create[IO](config.sink.maxBytes, config.sink, config.buffer, config.good)
      bad  <- PubSubSink.create[IO](config.sink.maxBytes, config.sink, config.buffer, config.bad)
    } yield Sinks(good, bad)
}
