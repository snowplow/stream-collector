/*
 * Copyright (c) 2013-2022 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, and
 * you may not use this file except in compliance with the Apache License
 * Version 2.0.  You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Apache License Version 2.0 is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream.integration.utils

import cats.effect.{Resource, Sync}
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.kinesis.{AmazonKinesis, AmazonKinesisClientBuilder}
import com.amazonaws.services.kinesis.model.GetRecordsRequest

object Kinesis {
  val GoodStreamName = "good"
  val BadStreamName  = "bad"

  def mkKinesisClient[F[_]: Sync](
    localstackPort: Int,
    accessKey: String      = "test",
    secretKey: String      = "test",
    endpointDomain: String = "localhost",
    endpointRegion: String = "eu-central-1"
  ): Resource[F, AmazonKinesis] = Resource.pure[F, AmazonKinesis](
    AmazonKinesisClientBuilder
      .standard()
      .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)))
      .withEndpointConfiguration(new EndpointConfiguration(s"http://$endpointDomain:$localstackPort", endpointRegion))
      .build
  )

  def getResult[F[_]: Sync](
    kinesis: AmazonKinesis,
    streamName: String,
    shardIdx: Int             = 0,
    shardIteratorType: String = "TRIM_HORIZON"
  ): F[Int] = {
    val shardId           = kinesis.describeStream(streamName).getStreamDescription.getShards.get(shardIdx).getShardId
    val iterator          = kinesis.getShardIterator(streamName, shardId, shardIteratorType).getShardIterator
    val getRecordsRequest = new GetRecordsRequest().withShardIterator(iterator)

    Sync[F].delay(kinesis.getRecords(getRecordsRequest).getRecords.size)
  }
}
