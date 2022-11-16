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

import cats.effect.{IO, Sync}
import cats.effect.testing.specs2.CatsIO
import com.snowplowanalytics.snowplow.collectors.scalastream.intergation.TestUtils._
import org.specs2.mutable.Specification

class KinesisSpec extends Specification with CatsIO {
  "The Kinesis collector should" >> {
    "ensure no good events are lost" in {
      val localstack = Containers.localstack
      val collector  = Containers.collector(localstack)

      lazy val localstackPort = Containers.getExposedPort(localstack, 4566)
      lazy val collectorPort  = Containers.getExposedPort(collector, 12345)

      val resources = for {
        localstack <- Containers.mkContainer[IO](localstack, "localstack")
        collector  <- Containers.mkContainer[IO](collector, "collector")
        kinesis    <- Kinesis.mkKinesisClient[IO](localstackPort)
        httpClient <- Http.mkHttpClient[IO]
        executor   <- Http.mkExecutor[IO]
      } yield (localstack, collector, kinesis, httpClient, executor)

      resources.use {
        case (_, _, kinesis, httpClient, executor) =>
          val requestStubs = EventGenerator.makeStubs(10, 50)
          val requests     = requestStubs.map(Http.makeRequest(_, collectorPort))

          for {
            _          <- Http.send[IO](requests)(httpClient, executor)
            _          <- Sync[IO].delay(Thread.sleep(10000)) // allow time for all records to be written before trying to read them
            numRecords <- Kinesis.getResult[IO](kinesis)
          } yield (numRecords shouldEqual requests.size)
      }
    }
  }
}
