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

import cats.effect.{Resource, Sync}
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.kinesis.model.GetRecordsRequest
import com.amazonaws.services.kinesis.{AmazonKinesis, AmazonKinesisClientBuilder}
import com.snowplowanalytics.snowplow.eventgen.tracker.{HttpRequest => RequestStub}
import com.snowplowanalytics.snowplow.eventgen.tracker.HttpRequest.Method.{Get, Head, Post}
import org.scalacheck.Gen

import java.net.URI
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.util.concurrent.ScheduledThreadPoolExecutor
import scala.compat.java8.FutureConverters.CompletionStageOps
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.MILLISECONDS
import scala.util.{Failure, Success}

object TestUtils {
  object Kinesis {
    def mkKinesisClient[F[_]: Sync](localstackPort: Int): Resource[F, AmazonKinesis] = Resource.pure(
      AmazonKinesisClientBuilder
        .standard()
        .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("test", "test")))
        .withEndpointConfiguration(new EndpointConfiguration(s"http://localhost:$localstackPort", "eu-central-1"))
        .build
    )

    def getResult[F[_]: Sync](kinesis: AmazonKinesis): F[Int] = {
      val shardId           = kinesis.describeStream("good").getStreamDescription.getShards.get(0).getShardId
      val iterator          = kinesis.getShardIterator("good", shardId, "TRIM_HORIZON").getShardIterator
      val getRecordsRequest = new GetRecordsRequest().withShardIterator(iterator)

      Sync[F].delay(kinesis.getRecords(getRecordsRequest).getRecords.size())
    }
  }

  object Http {
    def mkHttpClient[F[_]: Sync]: Resource[F, HttpClient] = Resource.pure(HttpClient.newBuilder().build())

    def mkExecutor[F[_]: Sync]: Resource[F, ScheduledThreadPoolExecutor] = Resource.pure(new ScheduledThreadPoolExecutor(10))

    def makeRequest(reqStub: RequestStub, collectorPort: Int): HttpRequest = {
      val method = reqStub.method match {
        case Post(_) => "POST"
        case Get(_)  => "GET"
        case Head(_) => "HEAD"
      }

      val path        = reqStub.method.path.toString
      val querystring = reqStub.qs.map(_.toString()).getOrElse("")
      val uri         = new URI("http", "", "0.0.0.0", collectorPort, path, querystring, "")
      val headers     = reqStub.headers.toList.flatMap { case (k, v) => List(k, v) }
      val payload     = reqStub.body.map(b => BodyPublishers.ofString(b.toString)).getOrElse(BodyPublishers.noBody())

      HttpRequest.newBuilder().uri(uri).headers(headers: _*).method(method, payload).build()
    }

    def scheduleRequest(
      request: HttpRequest,
      attempts: Int
    )(httpClient: HttpClient, executor: ScheduledThreadPoolExecutor): Unit = {
      val Backoff = 500L
      executor.schedule(
        new Runnable {
          override def run(): Unit =
            if (attempts >= 5) throw new RuntimeException(s"Still failing after 5 attempts: $request")
            else sendRequest(request, attempts)(httpClient, executor)
        },
        Backoff,
        MILLISECONDS
      )
      ()
    }

    def sendRequest(
      request: HttpRequest,
      attempts: Int
    )(httpClient: HttpClient, executor: ScheduledThreadPoolExecutor): Unit = {
      val responseFuture = httpClient.sendAsync(request, BodyHandlers.ofString()).toScala
      responseFuture.onComplete {
        case Success(response) => println(s"Got ${response.statusCode} for request $request")
        case Failure(reason) =>
          println(s"Ooops! $request failed because of ${reason.getMessage}!")
          scheduleRequest(request, attempts + 1)(httpClient, executor)
      }
    }

    def send[F[_]: Sync](
      requests: List[HttpRequest]
    )(httpClient: HttpClient, executor: ScheduledThreadPoolExecutor): F[Unit] =
      Sync[F].delay(requests.foreach(sendRequest(_, 1)(httpClient, executor)))
  }

  object EventGenerator {
    def makeStubs(min: Int, max: Int): List[RequestStub] =
      (for {
        n    <- Gen.chooseNum(min, max)
        reqs <- Gen.listOfN(n, RequestStub.gen(1, 5, java.time.Instant.now))
      } yield reqs)
        .apply(org.scalacheck.Gen.Parameters.default, org.scalacheck.rng.Seed(scala.util.Random.nextLong()))
        .getOrElse(List.empty)
  }
}
