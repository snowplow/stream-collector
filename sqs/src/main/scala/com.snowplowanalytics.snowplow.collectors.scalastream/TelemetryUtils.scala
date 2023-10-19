package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect.{IO, Resource}
import com.snowplowanalytics.snowplow.collector.core.Config
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks._

object TelemetryUtils {

  def getAccountId(config: Config.Streams[SqsSinkConfig]): IO[Option[String]] =
    Resource
      .make(
        IO(SqsSink.createSqsClient(config.good.config.region)).rethrow
      )(c => IO(c.shutdown()))
      .use { client =>
        IO {
          val sqsQueueUrl = client.getQueueUrl(config.good.name).getQueueUrl
          Some(extractAccountId(sqsQueueUrl))
        }
      }
      .handleError(_ => None)

  def extractAccountId(sqsQueueUrl: String): String =
    sqsQueueUrl.split("/")(3)

}
