package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import cats.effect._

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient

object SqsClient {
  def create[F[_]: Sync](config: SqsSinkConfig): Resource[F, SqsAsyncClient] =
    Resource.fromAutoCloseable(
      Sync[F].delay {
        SqsAsyncClient.builder().region(Region.of(config.region)).build()
      }
    )
}
