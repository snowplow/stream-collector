package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect.IO
import software.amazon.awssdk.http.async.SdkAsyncHttpClient

import com.snowplowanalytics.snowplow.collector.core.Config
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.{KinesisOps, KinesisSinkConfig}

object TelemetryUtils {

  def getAccountId(httpClient: SdkAsyncHttpClient, config: Config.Streams[KinesisSinkConfig]): IO[Option[String]] =
    KinesisOps
      .resource[IO](httpClient, config.good.name, config.good.config)
      .use { kinesisOps =>
        kinesisOps.describeStreamSummary.map { response =>
          val streamArn = response.streamDescriptionSummary().streamARN()
          Some(extractAccountId(streamArn))
        }
      }
      .handleError(_ => None)

  def extractAccountId(kinesisStreamArn: String): String =
    kinesisStreamArn.split(":")(4)

}
