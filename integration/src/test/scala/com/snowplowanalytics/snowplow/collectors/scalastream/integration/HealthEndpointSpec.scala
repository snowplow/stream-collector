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

import cats.effect.{IO, Resource}
import cats.effect.testing.specs2.CatsIO
import com.snowplowanalytics.snowplow.collectors.scalastream.integration.utils.{Containers, Http}
import com.snowplowanalytics.snowplow.collectors.scalastream.integration.utils.Http._
import org.specs2.mutable.Specification
import org.testcontainers.containers.{GenericContainer => JGenericContainer}

import java.net.URI
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.{HttpClient, HttpRequest}

class HealthEndpointSpec extends Specification with CatsIO {
  "The collector" should {
    def resources(collector: JGenericContainer[_]): Resource[IO, (JGenericContainer[_], HttpClient)] =
      for {
        collector  <- Containers.mkContainer[IO](collector)
        httpClient <- Http.mkHttpClient[IO]
      } yield (collector, httpClient)

    "respond with 200 OK to requests made to its health endpoint" in {
      val CollectorEndpointScheme     = "http"
      val CollectorHealthEndpointPath = "/health"

      val testConfig = Map(
        "COLLECTOR_INTERFACE" -> Containers.CollectorInterface,
        "COLLECTOR_PORT"      -> Containers.CollectorExposedPort.toString
      )

      val collector = Containers.collector("stdout", testConfig)

      resources(collector).use {
        case (collector, httpClient) =>
          val collectorPort = Containers.getExposedPort(collector, Containers.CollectorExposedPort)
          val uri = new URI(
            CollectorEndpointScheme,
            "",
            Containers.CollectorInterface,
            collectorPort,
            CollectorHealthEndpointPath,
            "",
            ""
          )
          val request = HttpRequest.newBuilder().uri(uri).method("GET", BodyPublishers.noBody()).build()

          Http.sendOne[IO](request, httpClient).map(getCode(_) mustEqual 200)
      }
    }
  }
}
