package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect.{IO, Resource}

import com.snowplowanalytics.snowplow.collector.core.Config
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.{KinesisSink, KinesisSinkConfig}

object TelemetryUtils {

  def getAccountId(config: Config.Streams[KinesisSinkConfig]): IO[Option[String]] =
    Resource
      .make(
        IO(KinesisSink.createKinesisClient(config.good.config.endpoint, config.good.config.region)).rethrow
      )(c => IO(c.close()))
      .use { kinesis =>
        IO {
          val streamArn = KinesisSink.describeStream(kinesis, config.good.name).streamARN()
          Some(extractAccountId(streamArn))
        }
      }
      .handleError(_ => None)

  def extractAccountId(kinesisStreamArn: String): String =
    kinesisStreamArn.split(":")(4)

}
