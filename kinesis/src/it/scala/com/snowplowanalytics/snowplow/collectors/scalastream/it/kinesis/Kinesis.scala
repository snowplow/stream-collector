/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
 * located at https://docs.snowplow.io/limited-use-license-1.1
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis

import scala.jdk.CollectionConverters._
import java.net.URI

import cats.effect.{IO, Resource}

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.auth.credentials._
import software.amazon.awssdk.services.kinesis.KinesisClient
import software.amazon.awssdk.services.kinesis.model._

import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload

import com.snowplowanalytics.snowplow.badrows.BadRow

import com.snowplowanalytics.snowplow.collectors.scalastream.it.CollectorOutput
import com.snowplowanalytics.snowplow.collectors.scalastream.it.utils._

import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.containers.Localstack

object Kinesis {

  def readOutput(streamGood: String, streamBad: String): IO[CollectorOutput] =
    resourceClient.use { client =>
      for {
        good <- consumeGood(client, streamGood)
        bad <- consumeBad(client, streamBad)
      } yield CollectorOutput(good, bad)
    }

  private def resourceClient: Resource[IO, KinesisClient] =
    Resource.make(IO(
      KinesisClient.builder()
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("whatever", "whatever")))
        .region(Region.of(Localstack.region))
        .endpointOverride(URI.create(Localstack.publicEndpoint))
        .build
    ))(client => IO(client.close()))

  private def consumeGood(
    kinesis: KinesisClient,
    streamName: String,
  ): IO[List[CollectorPayload]] =
    for {
      raw <- consumeStream(kinesis, streamName)
      good <- IO(raw.map(parseCollectorPayload))
    } yield good

  private def consumeBad(
    kinesis: KinesisClient,
    streamName: String,
  ): IO[List[BadRow]] =
    for {
      raw <- consumeStream(kinesis, streamName)
      bad <- IO(raw.map(parseBadRow))
    } yield bad

  private def consumeStream(
    kinesis: KinesisClient,
    streamName: String,
  ): IO[List[Array[Byte]]] = {
    val describeRequest = DescribeStreamRequest.builder().streamName(streamName).build()
    val shardId = kinesis.describeStream(describeRequest).streamDescription().shards().get(0).shardId()

    val getShardIteratorRequest = GetShardIteratorRequest.builder()
      .streamName(streamName)
      .shardId(shardId)
      .shardIteratorType("TRIM_HORIZON")
      .build()
    val iterator = kinesis.getShardIterator(getShardIteratorRequest).shardIterator()
    val getRecordsRequest = GetRecordsRequest.builder().shardIterator(iterator).build()

    IO(kinesis.getRecords(getRecordsRequest).records().asScala.toList.map(_.data().asByteArray()))
  }
}
