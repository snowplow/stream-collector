package com.snowplowanalytics.snowplow.collector.stdout

import cats.effect.Sync
import cats.effect.kernel.Resource

import com.snowplowanalytics.snowplow.collector.core.model.Sinks
import com.snowplowanalytics.snowplow.collector.core.App
import com.snowplowanalytics.snowplow.collector.core.Config

object StdoutCollector extends App[SinkConfig](BuildInfo) {

  override def mkSinks[F[_]: Sync](config: Config.Streams[SinkConfig]): Resource[F, Sinks[F]] = {
    val good = new PrintingSink(config.sink.maxBytes, System.out)
    val bad  = new PrintingSink(config.sink.maxBytes, System.err)
    Resource.pure(Sinks(good, bad))
  }
}
