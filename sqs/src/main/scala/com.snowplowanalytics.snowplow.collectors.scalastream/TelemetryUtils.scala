package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect.{IO, Resource}
import software.amazon.awssdk.services.sqs.model._
import com.snowplowanalytics.snowplow.collector.core.Config
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks._

object TelemetryUtils {

  def getAccountId(config: Config.Streams[SqsSinkConfig]): IO[Option[String]] =
    Resource
      .make(
        IO(SqsSink.createSqsClient(config.good.config.region)).rethrow
      )(c => IO(c.close()))
      .use { client =>
        IO {
          val req         = GetQueueUrlRequest.builder().queueName(config.good.name).build()
          val sqsQueueUrl = client.getQueueUrl(req).queueUrl()
          Some(extractAccountId(sqsQueueUrl))
        }
      }
      .handleError(_ => None)

  def extractAccountId(sqsQueueUrl: String): String =
    sqsQueueUrl.split("/")(3)

}
