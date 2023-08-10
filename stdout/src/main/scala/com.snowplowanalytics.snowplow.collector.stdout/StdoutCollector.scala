package com.snowplowanalytics.snowplow.collector.stdout

import cats.effect.IO
import cats.effect.kernel.Resource
import com.snowplowanalytics.snowplow.collector.core.model.Sinks
import com.snowplowanalytics.snowplow.collector.core.App
import com.snowplowanalytics.snowplow.collector.core.Config

object StdoutCollector extends App[SinkConfig](BuildInfo) {

  override def mkSinks(config: Config.Streams[SinkConfig]): Resource[IO, Sinks[IO]] = {
    val good = new PrintingSink[IO](config.sink.maxBytes, System.out)
    val bad  = new PrintingSink[IO](config.sink.maxBytes, System.err)
    Resource.pure(Sinks(good, bad))
  }
}
