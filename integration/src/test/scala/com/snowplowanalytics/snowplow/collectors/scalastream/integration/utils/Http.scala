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

import cats.effect.{ContextShift, Resource, Sync, Timer}
import cats.implicits._
import com.snowplowanalytics.snowplow.eventgen.collector.Api
import com.snowplowanalytics.snowplow.eventgen.tracker.{HttpRequest => RequestStub}
import com.snowplowanalytics.snowplow.eventgen.tracker.HttpRequest.Method.{Get, Head, Post}

import java.net.URI
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.http.HttpResponse.BodyHandlers
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.iterableAsScalaIterableConverter

object Http {
  def mkHttpClient[F[_]: Sync]: Resource[F, HttpClient] =
    Resource.pure[F, HttpClient](HttpClient.newBuilder().build())

  object Request {
    trait RequestType {
      def querystring: RequestStub => String
      def payload: RequestStub => HttpRequest.BodyPublisher
    }

    object RequestType {
      final case object Good extends RequestType {
        override def querystring: RequestStub => String = _.qs.map(_.toString()).getOrElse("")
        override def payload: RequestStub => HttpRequest.BodyPublisher =
          _.body.map(b => BodyPublishers.ofString(b.toString)).getOrElse(BodyPublishers.noBody())
      }

      final case object Bad extends RequestType {
        override def querystring: RequestStub => String = _ => ""
        override def payload: RequestStub => HttpRequest.BodyPublisher = _.body match {
          case None => BodyPublishers.noBody()
          case Some(_) =>
            val newBody = "s" * 192001
            BodyPublishers.ofString(newBody)
        }
      }
    }

    // Add @a as an element before each element of @as
    private def intersperse[A](a: A, as: List[A]): List[A] = as match {
      case Nil    => Nil
      case h :: t => List(a, h) ++ intersperse(a, t)
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
      val headers = reqStub.headers.toList.flatMap {
        case (k, v) if k != "Origin" => List(k, v)
        // We need to unpack multiple Origins in discrete key-value pairs
        // to play nicely with [HttpRequest.builder().headers()]
        case (k, v) => intersperse(k, v.split(",").toList)
      }
      val payload = reqType.payload(reqStub)

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

    def addDNT(stub: RequestStub): RequestStub = addHeader(stub, "Cookie", "dnt=dnt", ";")

    def addOrigin(stub: RequestStub, originDomain: String): RequestStub = addHeader(stub, "Origin", originDomain, ",")

    def addHeader(stub: RequestStub, headerName: String, headerValue: String, sep: String): RequestStub = {
      val headers = stub.headers
      val newHeaderValue = headers.get(headerName) match {
        case Some(v) =>
          s"$headerValue$sep$v" // Put the new value first, to account for logic like 'headers.collectFirst(`Origin`)' which we use in CollectorService
        case _ => headerValue
      }
      val newHeaders = headers ++ Map(headerName -> newHeaderValue)

      stub.copy(headers = newHeaders)
    }

    def setPath(stub: RequestStub, newPath: Api): RequestStub = {
      val newMethod = stub.method match {
        case Post(_) => Post(newPath)
        case Get(_)  => Get(newPath)
        case Head(_) => Head(newPath)
      }

      stub.copy(method = newMethod)
    }
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
