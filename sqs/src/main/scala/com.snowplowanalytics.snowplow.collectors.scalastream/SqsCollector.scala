package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect.IO
import cats.effect.kernel.Resource
import com.snowplowanalytics.snowplow.collector.core.{App, Config}
import com.snowplowanalytics.snowplow.collector.core.model.Sinks
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks._

object SqsCollector extends App[SqsSinkConfig](BuildInfo) {

  override def mkSinks(config: Config.Streams[SqsSinkConfig]): Resource[IO, Sinks[IO]] =
    for {
      client <- SqsClient.create[IO](config.sink)
      good   <- SqsSink.create[IO](client, config.good, config)
      bad    <- SqsSink.create[IO](client, config.bad, config)
    } yield Sinks(good, bad)

}
