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

import cats.effect.{IO, Resource}
import cats.effect.testing.specs2.CatsResourceIO
import com.snowplowanalytics.snowplow.collectors.scalastream.intergation.TestUtils.Http
import com.snowplowanalytics.snowplow.collectors.scalastream.intergation.TestUtils.Http._
import org.specs2.mutable.SpecificationLike
import org.testcontainers.containers.{GenericContainer => JGenericContainer}

import java.net.URI
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.{HttpClient, HttpRequest}

class StdoutSpec extends CatsResourceIO[(JGenericContainer[_], HttpClient)] with SpecificationLike {
  val collector = Containers.collector("stdout", "config")

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
  }
}
