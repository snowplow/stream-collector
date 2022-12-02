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

import cats.effect.IO
import cats.effect.testing.specs2.CatsIO
import cats.syntax.all._
import com.snowplowanalytics.snowplow.collectors.scalastream.intergation.CustomPathsSpec._
import com.snowplowanalytics.snowplow.collectors.scalastream.intergation.TestUtils.Http.Request.RequestType.Good
import com.snowplowanalytics.snowplow.collectors.scalastream.intergation.TestUtils.{Base64, EventGenerator, Http}
import com.snowplowanalytics.snowplow.eventgen.collector.Api
import org.specs2.matcher.MatchResult
import org.specs2.matcher.Matchers.beRight
import org.specs2.matcher.MustMatchers.theValue
import org.specs2.mutable.Specification
import org.testcontainers.containers.{GenericContainer => JGenericContainer}
import org.testcontainers.containers.output.{OutputFrame, WaitingConsumer}

import java.util.concurrent.{TimeUnit, TimeoutException}
import java.util.function.Predicate

class CustomPathsSpec extends Specification with CatsIO {
  "The collector" should {
    "correctly translate mapped custom paths" in {
      val collector    = Containers.collector("stdout", "custom-paths")
      val requestStubs = EventGenerator.makeStubs(3, 3)
      val paths        = List(Api("com.acme", "track"), Api("com.acme", "redirect"), Api("com.acme", "iglu"))

      val resources = for {
        collector  <- Containers.mkContainer[IO](collector)
        httpClient <- Http.mkHttpClient[IO]
      } yield (collector, httpClient)

      resources.use {
        case (collector, httpClient) =>
          val collectorPort = Containers.getExposedPort(collector, 12345)
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
  def matchLogs(container: JGenericContainer[_]): MatchResult[Either[TimeoutException, Unit]] = {
    val logConsumer = new WaitingConsumer
    val p = new Predicate[OutputFrame] {
      override def test(t: OutputFrame): Boolean = {
        val decoded = Base64.decode(t.getUtf8String)

        decoded.contains("/com.snowplowanalytics.snowplow/tp2") ||
          decoded.contains("/r/tp2") ||
          decoded.contains("/com.snowplowanalytics.iglu/v1")
      }
    }

    container.followOutput(logConsumer)
    Either.catchOnly[TimeoutException](logConsumer.waitUntil(p, 1, TimeUnit.SECONDS, 3)) must beRight
  }
}
