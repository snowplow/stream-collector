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
package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpMethods, HttpRequest, MediaRanges, Uri}
import akka.http.scaladsl.model.headers.{`Accept`, `Content-Type`}
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder
import com.amazonaws.services.kinesis.model.GetRecordsRequest
import com.dimafeng.testcontainers.DockerComposeContainer.ComposeFile
import com.dimafeng.testcontainers.{ContainerDef, DockerComposeContainer, ExposedService, ServiceLogConsumer}
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.scalatest.flatspec.AnyFlatSpec
import org.slf4j.LoggerFactory
import org.testcontainers.containers.output.Slf4jLogConsumer

import java.io.File
import scala.util.{Failure, Success}

// Before running this test locally, execute `sbt 'project Kinesis' 'Docker / stage'`
class NoDataLossSpec extends AnyFlatSpec with TestContainerForAll {
  val composeFile = ComposeFile(Left(new File(".github/workflows/integration_tests/no_data_loss/docker-compose.yml")))
  val exposedServices =
    List(ExposedService("localhost.localstack.cloud", 4566), ExposedService("snowplow.collector", 12345))

  lazy val lsLogger = LoggerFactory.getLogger(getClass)
  val lsLog         = new Slf4jLogConsumer(lsLogger)

  lazy val cLogger = LoggerFactory.getLogger(getClass)
  val cLog         = new Slf4jLogConsumer(cLogger)

  val logConsumers =
    List(ServiceLogConsumer("localhost.localstack.cloud", lsLog), ServiceLogConsumer("snowplow.collector", cLog))

  override val containerDef: ContainerDef = DockerComposeContainer.Def(
    composeFiles    = composeFile,
    exposedServices = exposedServices
  )

  it should "ensure no data is lost" in withContainers { _ =>
    lazy val kinesisClient = AmazonKinesisClientBuilder
      .standard()
      .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("test", "test")))
      .withEndpointConfiguration(new EndpointConfiguration("http://localhost:4566", "eu-central-1"))
      .build

    //Akka
    implicit val system           = ActorSystem()
    implicit val executionContext = system.dispatcher

    val payload =
      """{"schema":"iglu:com.snowplowanalytics.snowplow/payload_data/jsonschema/1-0-4","data":[{"p":"web","e":"pv","tv":"js-2.16.1"}]}"""

    val request = HttpRequest()
      .withMethod(HttpMethods.POST)
      .withUri(Uri(s"http://0.0.0.0:12345/com.snowplowanalytics.snowplow/tp2"))
      .withHeaders(List(`Accept`(MediaRanges.`*/*`), `Content-Type`(ContentTypes.`application/json`)))
      .withEntity(payload)

    def sendRequest(request: HttpRequest): Unit = {
      val responseFuture = Http().singleRequest(request)
      responseFuture.onComplete {
        case Success(response) => println(s"Got ${response.status} for request $request")
        case Failure(_)        => println(s"Ooops! $request failed!")
      }
    }

    val _ = {
      sendRequest(request)
      Thread.sleep(30000)

      val shardId           = kinesisClient.describeStream("good").getStreamDescription.getShards.get(0).getShardId
      val iterator          = kinesisClient.getShardIterator("good", shardId, "TRIM_HORIZON").getShardIterator
      val getRecordsRequest = new GetRecordsRequest().withShardIterator(iterator)
      val numRecords        = kinesisClient.getRecords(getRecordsRequest).getRecords.size()
      assert(numRecords == 1)
    }
  }
}
