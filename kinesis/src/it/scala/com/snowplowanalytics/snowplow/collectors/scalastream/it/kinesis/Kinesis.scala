/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.0
 * located at https://docs.snowplow.io/limited-use-license-1.1
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis

import scala.jdk.CollectionConverters._
import scala.collection.mutable.ArrayBuffer

import cats.effect.{IO, Resource}

import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.services.kinesis.{AmazonKinesis, AmazonKinesisClientBuilder}
import com.amazonaws.services.kinesis.model.{GetRecordsRequest, Record}

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

  private def resourceClient: Resource[IO, AmazonKinesis] =
    Resource.make(IO(
      AmazonKinesisClientBuilder
        .standard()
        .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("whatever", "whatever")))
        .withEndpointConfiguration(new EndpointConfiguration(Localstack.publicEndpoint, Localstack.region))
        .build
    ))(client => IO(client.shutdown()))

  private def consumeGood(
    kinesis: AmazonKinesis,
    streamName: String,
  ): IO[List[CollectorPayload]] =
    for {
      raw <- consumeStream(kinesis, streamName)
      good <- IO(raw.map(parseCollectorPayload))
    } yield good

  private def consumeBad(
    kinesis: AmazonKinesis,
    streamName: String,
  ): IO[List[BadRow]] =
    for {
      raw <- consumeStream(kinesis, streamName)
      bad <- IO(raw.map(parseBadRow))
    } yield bad

  private def consumeStream(
    kinesis: AmazonKinesis,
    streamName: String,
  ): IO[List[Array[Byte]]] = {
    val shardId = kinesis.describeStream(streamName).getStreamDescription.getShards.get(0).getShardId
    val iterator = kinesis.getShardIterator(streamName, shardId, "TRIM_HORIZON").getShardIterator
    val getRecordsRequest = new GetRecordsRequest().withShardIterator(iterator)

    IO(kinesis.getRecords(getRecordsRequest).getRecords.asScala.toList.map(getPayload))
  }

  def getPayload(record: Record): Array[Byte] = {
    val data = record.getData()
    val buffer = ArrayBuffer[Byte]()
    while (data.hasRemaining())
      buffer.append(data.get)
    buffer.toArray
  }
}
