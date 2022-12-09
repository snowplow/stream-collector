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

import cats.effect.IO
import cats.effect.testing.specs2.CatsIO
import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload
import org.specs2.matcher.Matchers.beTrue
import com.snowplowanalytics.snowplow.collectors.scalastream.integration.CustomPathsSpec._
import com.snowplowanalytics.snowplow.collectors.scalastream.integration.utils.Http.Request.RequestType.Good
import com.snowplowanalytics.snowplow.collectors.scalastream.integration.utils._
import com.snowplowanalytics.snowplow.eventgen.collector.Api
import org.apache.thrift.TDeserializer
import org.specs2.matcher.MatchResult
import org.specs2.matcher.MustMatchers.theValue
import org.specs2.mutable.Specification
import org.testcontainers.containers.output.OutputFrame.OutputType
import org.testcontainers.containers.{GenericContainer => JGenericContainer}

class CustomPathsSpec extends Specification with CatsIO {
  "The collector" should {
    "correctly translate mapped custom paths" in {
      val JavaOpts =
        "-Dcollector.paths./test/track=/com.snowplowanalytics.snowplow/tp2 -Dcollector.paths./test/redirect=/r/tp2 -Dcollector.paths./test/iglu=/com.snowplowanalytics.iglu/v1"

      val testConfig = Map(
        "COLLECTOR_COOKIE_ENABLED" -> "false",
        "JAVA_OPTS"                -> JavaOpts
      )
      val collector    = Containers.collector("stdout", testConfig)
      val requestStubs = EventGenerator.makeStubs(3, 3)
      val paths        = List(Api("test", "track"), Api("test", "redirect"), Api("test", "iglu"))

      val resources = for {
        collector  <- Containers.mkContainer[IO](collector)
        httpClient <- Http.mkHttpClient[IO]
      } yield (collector, httpClient)

      resources.use {
        case (collector, httpClient) =>
          val collectorPort = Containers.getExposedPort(collector, Containers.CollectorExposedPort)
          val requests = requestStubs
            .zip(paths)
            .map { case (stub, path) => Http.Request.setPath(stub, path) }
            .map(Http.Request.make(_, collectorPort, Good))

          Http.sendAll[IO](requests, httpClient).map(_ => matchLogs(collector))
      }
    }
  }
}

object CustomPathsSpec {
  def matchLogs(container: JGenericContainer[_]): MatchResult[Any] = {
    val logs    = container.getLogs(OutputType.STDOUT).split("\n").toList
    val decoded = logs.map(Base64.decode)
    val collectorPayloads = decoded.map { e =>
      val target = new CollectorPayload()
      new TDeserializer().deserialize(target, e)
      target
    }

    val expectedPaths = List("/com.snowplowanalytics.snowplow/tp2", "/r/tp2", "/com.snowplowanalytics.iglu/v1")

    collectorPayloads.zip(expectedPaths).map { case (cp, ep) => cp.path == ep }.forall(_ == true) must beTrue
  }
}
