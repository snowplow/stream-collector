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
package com.snowplowanalytics.snowplow.collectors.scalastream.intergation

import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder
import com.amazonaws.services.kinesis.model.GetRecordsRequest
import com.snowplowanalytics.snowplow.eventgen.tracker.{HttpRequest => SnowplowHttpReq}
import com.snowplowanalytics.snowplow.eventgen.tracker.HttpRequest.Method.{Get, Head, Post}
import org.scalacheck.Gen
import org.specs2.mutable.Specification
import org.specs2.specification.{AfterAll, BeforeAll}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest}
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.util.concurrent.ScheduledThreadPoolExecutor
import scala.compat.java8.FutureConverters.CompletionStageOps
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.MILLISECONDS
import scala.util.{Failure, Success}

class KinesisSpec extends Specification with BeforeAll with AfterAll {
  val localstack = Containers.localstack
  val collector  = Containers.collector(localstack)

  def beforeAll: Unit = {
    Containers.start(localstack, "localstack")
    Containers.start(collector, "collector")
    ()
  }

  def afterAll: Unit = {
    Containers.stop(localstack)
    Containers.stop(collector)
    ()
  }

  "The Kinesis collector should" >> {
    lazy val localstackPort = Containers.getExposedPort(localstack, 4566)
    lazy val collectorPort  = Containers.getExposedPort(collector, 12345)

    lazy val kinesisClient = AmazonKinesisClientBuilder
      .standard()
      .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("test", "test")))
      .withEndpointConfiguration(new EndpointConfiguration(s"http://localhost:$localstackPort", "eu-central-1"))
      .build

    val executorService = new ScheduledThreadPoolExecutor(10)

    val httpClient = HttpClient.newBuilder().build()

    def toRequest(req: SnowplowHttpReq): HttpRequest = {
      val method = req.method match {
        case Post(_) => "POST"
        case Get(_)  => "GET"
        case Head(_) => "HEAD"
      }

      val path        = req.method.path.toString
      val querystring = req.qs.map(_.toString()).getOrElse("")
      val uri         = new URI("http", "", "0.0.0.0", collectorPort, path, querystring, "")

      val headers = req.headers.toList.flatMap { case (k, v) => List(k, v) }

      val payload = req.body.map(b => BodyPublishers.ofString(b.toString)).getOrElse(BodyPublishers.noBody())

      HttpRequest.newBuilder().uri(uri).headers(headers: _*).method(method, payload).build()
    }

    def scheduleRequest(request: HttpRequest, attempts: Int): Unit = {
      val Backoff = 500L
      executorService.schedule(
        new Runnable {
          override def run(): Unit =
            if (attempts >= 5) throw new RuntimeException(s"Still failing after 5 attempts: $request")
            else sendRequest(request, attempts)
        },
        Backoff,
        MILLISECONDS
      )
      ()
    }

    def sendRequest(request: HttpRequest, attempts: Int): Unit = {
      val responseFuture = httpClient.sendAsync(request, BodyHandlers.ofString()).toScala
      responseFuture.onComplete {
        case Success(response) => println(s"Got ${response.statusCode} for request $request")
        case Failure(reason) =>
          println(s"Ooops! $request failed because of ${reason.getMessage}!")
          scheduleRequest(request, attempts + 1)
      }
    }

    "ensure no good events are lost" in {
      val requestStubs = (for {
        n    <- Gen.chooseNum(10, 50)
        reqs <- Gen.listOfN(n, SnowplowHttpReq.gen(1, 5, java.time.Instant.now))
      } yield reqs)
        .apply(org.scalacheck.Gen.Parameters.default, org.scalacheck.rng.Seed(scala.util.Random.nextLong()))
        .getOrElse(List.empty)

      val requests = requestStubs.map(toRequest)

      requests.foreach(sendRequest(_, 1))

      val shardId           = kinesisClient.describeStream("good").getStreamDescription.getShards.get(0).getShardId
      val iterator          = kinesisClient.getShardIterator("good", shardId, "TRIM_HORIZON").getShardIterator
      val getRecordsRequest = new GetRecordsRequest().withShardIterator(iterator)
      val numRecords        = kinesisClient.getRecords(getRecordsRequest).getRecords.size()

      numRecords shouldEqual requests.size
    }
  }
}
