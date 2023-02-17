/*
 * Copyright (c) 2022-2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.collectors.scalastream.it.pubsub

import scala.concurrent.duration._

import cats.effect.IO

import cats.effect.testing.specs2.CatsIO

import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterAll

import org.testcontainers.containers.GenericContainer

import com.snowplowanalytics.snowplow.collectors.scalastream.it.utils._
import com.snowplowanalytics.snowplow.collectors.scalastream.it.EventGenerator

class GooglePubSubCollectorSpec extends Specification with CatsIO with BeforeAfterAll {

  override protected val Timeout = 5.minutes

  def beforeAll: Unit = Containers.startEmulator()

  def afterAll: Unit = Containers.stopEmulator()

  val stopTimeout = 20.second

  val maxBytes = 10000

  "collector-pubsub" should {
    "be able to parse the minimal config" in {
      val testName = "minimal"
      Containers.collector(
        "examples/config.pubsub.minimal.hocon",
        testName
      ).use { collector =>
        IO(collector.getLogs() must contain(("Setting health endpoint to healthy")))
      }
    }

    "emit the correct number of collector payloads and bad rows" in {
      val testName = "count"
      val nbGood = 1000
      val nbBad = 10

      Containers.collector(
        "examples/config.pubsub.minimal.hocon",
        testName,
        Map("JDK_JAVA_OPTIONS" -> s"-Dcollector.streams.sink.maxBytes=$maxBytes")
      ).use { collector =>
        for {
          _ <- log(testName, "Sending data")
          _ <- EventGenerator.sendEvents(
            collector.getHost(),
            collector.getMappedPort(Containers.collectorPort),
            nbGood,
            nbBad,
            maxBytes
          )
          _ <- log(testName, "Data sent. Waiting for collector to work")
          _ <- IO.sleep(5.second)
          _ <- log(testName, "Consuming collector's output")
          collectorOutput <- PubSub.consume(
            Containers.projectId,
            Containers.emulatorHost,
            Containers.emulatorHostPort,
            Containers.topicGood,
            Containers.topicBad
          )
          _ <- printBadRows(testName, collectorOutput.bad)
        } yield {
          collectorOutput.good.size should beEqualTo(nbGood)
          collectorOutput.bad.size should beEqualTo(nbBad)
        }
      }
    }

    s"shutdown within $stopTimeout when it receives a SIGTERM" in {
      val testName = "stop"
      Containers.collector(
        "examples/config.pubsub.minimal.hocon",
        testName
      ).use { collector =>
        for {
          _ <- log(testName, "Sending signal")
          _ <- IO(collector.getDockerClient().killContainerCmd(collector.getContainerId()).withSignal("TERM").exec())
          _ <- waitWhile[GenericContainer[_]](collector, _.isRunning, stopTimeout)
        } yield {
          collector.isRunning() must beFalse
          collector.getLogs() must contain("Server terminated")
        }
      }
    }
  }
}
