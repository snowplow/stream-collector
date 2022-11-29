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

import cats.effect.{IO, Resource, Sync}
import cats.effect.testing.specs2.CatsResourceIO
import cats.implicits.toTraverseOps
import com.snowplowanalytics.snowplow.collectors.scalastream.intergation.TestUtils.Http.Request.RequestType.Good
import com.snowplowanalytics.snowplow.collectors.scalastream.intergation.TestUtils.{EventGenerator, Http}
import com.snowplowanalytics.snowplow.collectors.scalastream.intergation.TestUtils.Http._
import org.specs2.mutable.SpecificationLike
import org.testcontainers.containers.{GenericContainer => JGenericContainer}

import java.net.URI
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.{HttpClient, HttpRequest}
import scala.compat.java8.OptionConverters.RichOptionalGeneric

class StdoutSpec extends CatsResourceIO[(JGenericContainer[_], HttpClient)] with SpecificationLike {
  val collector = Containers.collector("stdout")

  val resource: Resource[IO, (JGenericContainer[_], HttpClient)] = for {
    collector  <- Containers.mkContainer(collector)
    httpClient <- Http.mkHttpClient[IO]
  } yield (collector, httpClient)

  "The collector" should {
    "respond with 200 OK to requests made to its health endpoint" in withResource {
      case (collector, httpClient) =>
        val collectorPort = Containers.getExposedPort(collector, 12345)
        val uri           = new URI("http", "", "0.0.0.0", collectorPort, "/health", "", "")
        val request       = HttpRequest.newBuilder().uri(uri).method("GET", BodyPublishers.noBody()).build()

        Http.sendOne[IO](request, httpClient).map(getCode(_) mustEqual 200)
    }

    "respond with a `Set-Cookie` header if the request has an `SP-Anonymous: *` header, and with none otherwise" in withResource {
      case (collector, httpClient) =>
        val collectorPort = Containers.getExposedPort(collector, 12345)
        val requestStubs  = EventGenerator.makeStubs(10, 50)
        val good          = requestStubs.map(Http.Request.make(_, collectorPort, Good))

        for {
          responses <- good.traverse(sendOne(_, httpClient))
          res <- Sync[IO].delay(responses.zip(good).map {
            case (resp, req) =>
              (resp.headers.firstValue("Set-Cookie").asScala, req.headers.firstValue("SP-Anonymous").asScala)
          })
        } yield res.map {
          case (cookieHeader, anonymousHeader) =>
            if (anonymousHeader.isDefined) cookieHeader must beNone else cookieHeader must beSome
        }
    }
  }
}
