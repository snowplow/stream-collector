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
package com.snowplowanalytics.snowplow.collectors.scalastream.integration

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ContentTypes, HttpMethods, HttpRequest, Uri}
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder
import com.amazonaws.services.kinesis.model.GetRecordsRequest
import com.dimafeng.testcontainers.DockerComposeContainer.ComposeFile
import com.dimafeng.testcontainers.{ContainerDef, DockerComposeContainer, ExposedService, ServiceLogConsumer}
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import com.snowplowanalytics.snowplow.eventgen.tracker.HttpRequest.Method.{Get, Head, Post}
import org.scalatest.flatspec.AnyFlatSpec
import org.slf4j.LoggerFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
import com.snowplowanalytics.snowplow.eventgen.tracker.{HttpRequest => SnowplowHttpReq}
import org.scalacheck.Gen

import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import scala.concurrent.duration.MILLISECONDS
import scala.util.{Failure, Success}

class NoDataLossKinesisSpec extends AnyFlatSpec with TestContainerForAll {
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

    val executorService = new ScheduledThreadPoolExecutor(10)

    val requestStubs = (for {
      n <- Gen.chooseNum(10, 50)
      reqs <- Gen.listOfN(n, SnowplowHttpReq.gen(1, 5, java.time.Instant.now))
    } yield reqs).apply(org.scalacheck.Gen.Parameters.default, org.scalacheck.rng.Seed(scala.util.Random.nextLong())).getOrElse(List.empty)

    def toRequest(req: SnowplowHttpReq): HttpRequest = {
      val method = req.method match {
        case Post(_) => HttpMethods.POST
        case Get(_) => HttpMethods.GET
        case Head(_) => HttpMethods.HEAD
      }

      val path = req.method.path.toString
      val querystring = req.qs.map(_.toString())
      val uri = Uri.from("http", "", "0.0.0.0", 12345, path, querystring)

      val headers = req.headers.toList.filterNot(_._1 == "Content-Type").map { case (k, v) => RawHeader(k, v) } // annoyingly, Content-Type can't be passed in as RawHeader

      val payload = req.body.map (_.toString () ).getOrElse ("")

      HttpRequest()
        .withMethod(method)
        .withUri(uri)
        .withHeaders(headers)
        .withEntity(ContentTypes.`application/json`, payload)
    }

    val requests = requestStubs.map(toRequest)

    def scheduleRequest(request: HttpRequest, attempts: Int): Unit = {
      val Backoff = 500L
      executorService.schedule(
        new Runnable {
          override def run(): Unit = if (attempts >= 5) throw new RuntimeException(s"Still failing after 5 attempts: $request") else sendRequest(request, attempts)
        },
        Backoff,
        MILLISECONDS
      )
      ()
    }

    def sendRequest(request: HttpRequest, attempts: Int): Unit = {
      val responseFuture = Http().singleRequest(request)
      responseFuture.onComplete {
        case Success(response) => println(s"Got ${response.status} for request $request")
        case Failure(reason) =>
          println(s"Ooops! $request failed because of ${reason.getMessage}!")
          scheduleRequest(request, attempts + 1)
      }
    }

    val _ = {
      requests.foreach(sendRequest(_, 1))
      Thread.sleep(10000)

      val shardId           = kinesisClient.describeStream("good").getStreamDescription.getShards.get(0).getShardId
      val iterator          = kinesisClient.getShardIterator("good", shardId, "TRIM_HORIZON").getShardIterator
      val getRecordsRequest = new GetRecordsRequest().withShardIterator(iterator)
      val numRecords        = kinesisClient.getRecords(getRecordsRequest).getRecords.size()

      assert(numRecords == requests.size)
    }
  }
}
