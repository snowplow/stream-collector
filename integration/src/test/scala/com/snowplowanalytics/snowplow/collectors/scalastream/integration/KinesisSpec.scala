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

import cats.effect.{IO, Sync}
import cats.effect.testing.specs2.CatsIO
import com.snowplowanalytics.snowplow.collectors.scalastream.integration.utils._
import com.snowplowanalytics.snowplow.collectors.scalastream.integration.utils.Http.Request.RequestType.{Bad, Good}
import com.snowplowanalytics.snowplow.eventgen.tracker.HttpRequest.Method.Post
import org.specs2.mutable.Specification

class KinesisSpec extends Specification with CatsIO {
  "The Kinesis collector" should {
    "ensure all good and bad events are written to the sink" in {
      val testConfig = Map(
        "COLLECTOR_INTERFACE"                    -> Containers.CollectorInterface,
        "COLLECTOR_PORT"                         -> Containers.CollectorExposedPort.toString,
        "COLLECTOR_COOKIE_ENABLED"               -> "true",
        "COLLECTOR_COOKIE_EXPIRATION"            -> "365 days",
        "COLLECTOR_COOKIE_NAME"                  -> "sp",
        "COLLECTOR_COOKIE_SECURE"                -> "false",
        "COLLECTOR_COOKIE_HTTP_ONLY"             -> "false",
        "COLLECTOR_STREAMS_SINK_REGION"          -> "eu-central-1",
        "COLLECTOR_STREAMS_SINK_CUSTOM_ENDPOINT" -> "http://localstack:4566",
        "COLLECTOR_STREAMS_SINK_SQS_GOOD"        -> "good",
        "COLLECTOR_STREAMS_SINK_SQS_BAD"         -> "bad",
        "COLLECTOR_STREAMS_SINK_AWS_ACCESS_KEY"  -> "env",
        "COLLECTOR_STREAMS_SINK_AWS_SECRET_KEY"  -> "env"
      )

      val localstack = Containers.localstack
      val collector  = Containers.collector("kinesis", testConfig)

      lazy val localstackPort = Containers.getExposedPort(localstack, Containers.LocalstackExposedPort)
      lazy val collectorPort  = Containers.getExposedPort(collector, Containers.CollectorExposedPort)

      val resources = for {
        _          <- Containers.mkContainer[IO](localstack)
        _          <- Containers.mkContainer[IO](collector)
        kinesis    <- Kinesis.mkKinesisClient[IO](localstackPort)
        httpClient <- Http.mkHttpClient[IO]
      } yield (kinesis, httpClient)

      resources.use {
        case (kinesis, httpClient) =>
          val requestStubs = EventGenerator.makeStubs(10, 50)
          val good         = requestStubs.map(Http.Request.make(_, collectorPort, Good))
          val bad =
            requestStubs
              .filter(req =>
                req.method match {
                  case Post(_) if req.body.isDefined => true
                  case _                             => false
                }
              )
              .map(Http.Request.make(_, collectorPort, Bad))

          val requests = good ++ bad

          for {
            _       <- Sync[IO].delay(println(s"Sending ${good.size} good and ${bad.size} bad events."))
            _       <- Http.sendAll[IO](requests, httpClient)
            _       <- Sync[IO].delay(Thread.sleep(10000)) // allow time for all records to be written before trying to read them
            numGood <- Kinesis.getResult[IO](kinesis, Kinesis.GoodStreamName)
            numBad  <- Kinesis.getResult[IO](kinesis, Kinesis.BadStreamName)
          } yield {
            numGood shouldEqual good.size
            numBad shouldEqual bad.size
          }
      }
    }
  }
}
