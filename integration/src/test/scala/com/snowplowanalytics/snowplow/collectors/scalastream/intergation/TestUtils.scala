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

import cats.effect.{ContextShift, Resource, Sync, Timer}
import cats.implicits._
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
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.iterableAsScalaIterableConverter

object TestUtils {
  object Kinesis {
    def mkKinesisClient[F[_]: Sync](localstackPort: Int): Resource[F, AmazonKinesis] = Resource.pure[F, AmazonKinesis](
      AmazonKinesisClientBuilder
        .standard()
        .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("test", "test")))
        .withEndpointConfiguration(new EndpointConfiguration(s"http://localhost:$localstackPort", "eu-central-1"))
        .build
    )

    def getResult[F[_]: Sync](kinesis: AmazonKinesis, streamName: String): F[Int] = {
      val shardId           = kinesis.describeStream(streamName).getStreamDescription.getShards.get(0).getShardId
      val iterator          = kinesis.getShardIterator(streamName, shardId, "TRIM_HORIZON").getShardIterator
      val getRecordsRequest = new GetRecordsRequest().withShardIterator(iterator)

      Sync[F].delay(kinesis.getRecords(getRecordsRequest).getRecords.size)
    }
  }

  object Http {
    def mkHttpClient[F[_]: Sync]: Resource[F, HttpClient] =
      Resource.pure[F, HttpClient](HttpClient.newBuilder().build())

    object Request {
      trait RequestType {
        def querystring: RequestStub => String
        def payload: RequestStub     => HttpRequest.BodyPublisher
      }

      object RequestType {
        final case object Good extends RequestType {
          override def querystring: RequestStub => String = _.qs.map(_.toString()).getOrElse("")
          override def payload: RequestStub     => HttpRequest.BodyPublisher =
            _.body.map(b => BodyPublishers.ofString(b.toString)).getOrElse(BodyPublishers.noBody())
        }

        final case object Bad extends RequestType {
          override def querystring: RequestStub => String = _ => ""
          override def payload: RequestStub     => HttpRequest.BodyPublisher = _.body match {
            case None => BodyPublishers.noBody()
            case Some(_) =>
              val newBody = "s" * 192001
              BodyPublishers.ofString(newBody)
          }
        }
      }

      def make(reqStub: RequestStub, collectorPort: Int, reqType: RequestType): HttpRequest = {
        val method = reqStub.method match {
          case Post(_) => "POST"
          case Get(_)  => "GET"
          case Head(_) => "HEAD"
        }

        val path        = reqStub.method.path.toString
        val querystring = reqType.querystring(reqStub)
        val uri         = new URI("http", "", "0.0.0.0", collectorPort, path, querystring, "")
        val headers     = reqStub.headers.toList.flatMap { case (k, v) => List(k, v) }
        val payload     = reqType.payload(reqStub)

        HttpRequest.newBuilder().uri(uri).headers(headers: _*).method(method, payload).build()
      }

      def send[F[_]: Sync](request: HttpRequest, httpClient: HttpClient): F[HttpResponse[String]] =
        Sync[F].delay(httpClient.send(request, BodyHandlers.ofString()))

      def withRetry[F[_]: Sync: Timer: ContextShift](
        f: F[HttpResponse[String]],
        maxRetries: Int
      ): F[HttpResponse[String]] = {
        val Backoff = 500.milliseconds
        f.handleErrorWith { error =>
          if (maxRetries > 0)
            Timer[F].sleep(Backoff) *> withRetry(f, maxRetries - 1)
          else Sync[F].raiseError(error)
        }
      }

      def sendWithRetry[F[_]: Sync: Timer: ContextShift](
        request: HttpRequest,
        httpClient: HttpClient,
        maxRetries: Int
      ): F[HttpResponse[String]] = withRetry(send(request, httpClient), maxRetries)
    }

    def sendAll[F[_]: Sync: Timer: ContextShift](
      requests: List[HttpRequest],
      httpClient: HttpClient
    ): F[List[HttpResponse[String]]] =
      requests.traverse[F, HttpResponse[String]](req => Request.sendWithRetry(req, httpClient, 5))

    def sendOne[F[_]: Sync](request: HttpRequest, httpClient: HttpClient): F[HttpResponse[String]] =
      Request.send(request, httpClient)

    def getCode(response: HttpResponse[_]): Int = response.statusCode()

    def getHeader(response: HttpResponse[_], headerName: String): List[String] =
      response.headers().allValues(headerName).asScala.toList
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
