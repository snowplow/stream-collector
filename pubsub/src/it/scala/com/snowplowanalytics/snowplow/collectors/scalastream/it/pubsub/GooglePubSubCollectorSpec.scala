/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
 * located at https://docs.snowplow.io/limited-use-license-1.1
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream.it.pubsub

import scala.concurrent.duration._
import cats.effect.IO
import org.http4s.{Method, Request, Status, Uri}
import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterAll
import org.testcontainers.containers.GenericContainer
import com.snowplowanalytics.snowplow.collectors.scalastream.it.utils._
import com.snowplowanalytics.snowplow.collectors.scalastream.it.{EventGenerator, Http}

class GooglePubSubCollectorSpec extends Specification with CatsEffect with BeforeAfterAll {

  override protected val Timeout = 5.minutes

  def beforeAll(): Unit = Containers.startEmulator()

  def afterAll(): Unit = Containers.stopEmulator()

  val stopTimeout = 20.second

  val maxBytes = 10000

  "collector-pubsub" should {
    "be able to parse the minimal config" in {
      val testName = "minimal"

      Containers.collector(
        "examples/config.pubsub.minimal.hocon",
        testName,
        "good",
        "bad"
      ).use { collector =>
        IO(collector.container.getLogs() must contain("Service bound to address"))
      }
    }

    "emit the correct number of collector payloads and bad rows" in {
      val testName = "count"
      val nbGood = 1000
      val nbBad = 10
      val topicGood = s"${testName}-raw"
      val topicBad = s"${testName}-bad-1"

      Containers.collector(
        "pubsub/src/it/resources/collector.hocon",
        testName,
        topicGood,
        topicBad,
        envs = Map("MAX_BYTES" -> maxBytes.toString)
      ).use { collector =>
        for {
          _ <- log(testName, "Sending data")
          _ <- EventGenerator.sendEvents(
            collector.host,
            collector.port,
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
            topicGood,
            topicBad
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
        "pubsub/src/it/resources/collector.hocon",
        testName,
        s"${testName}-raw",
        s"${testName}-bad-1"
      ).use { collector =>
        val container = collector.container
        for {
          _ <- log(testName, "Sending signal")
          _ <- IO(container.getDockerClient().killContainerCmd(container.getContainerId()).withSignal("TERM").exec())
          _ <- waitWhile[GenericContainer[_]](container, _.isRunning, stopTimeout)
        } yield {
          container.isRunning() must beFalse
          container.getLogs() must contain("Closing NIO1 channel")
        }
      }
    }

    "start with /sink-health unhealthy and insert pending events when topics become available" in {
      val testName = "sink-health"
      val nbGood = 10
      val nbBad = 10
      val topicGood = s"${testName}-raw"
      val topicBad = s"${testName}-bad-1"

      Containers.collector(
        "pubsub/src/it/resources/collector.hocon",
        testName,
        topicGood,
        topicBad,
        createTopics = false,
        envs = Map("MAX_BYTES" -> maxBytes.toString)
      ).use { collector =>
        val uri = Uri.unsafeFromString(s"http://${collector.host}:${collector.port}/sink-health")
        val request = Request[IO](Method.GET, uri)

        for {
          _ <- log(testName, "Checking /sink-health before creating the topics")
          statusBeforeCreate <- Http.status(request)
          _ <- log(testName, "Sending events before creating the topics")
          _ <- EventGenerator.sendEvents(
            collector.host,
            collector.port,
            nbGood,
            nbBad,
            maxBytes
          )
          _ <- log(testName, "Creating topics")
          _ <- PubSub.createTopicsAndSubscriptions(
            Containers.projectId,
            Containers.emulatorHost,
            Containers.emulatorHostPort,
            List(topicGood, topicBad)
          )
          _ <- IO.sleep(10.second)
          _ <- log(testName, "Checking /sink-health after creating the topics")
          statusAfterCreate <- Http.status(request)
          collectorOutput <- PubSub.consume(
            Containers.projectId,
            Containers.emulatorHost,
            Containers.emulatorHostPort,
            topicGood,
            topicBad
          )
          _ <- printBadRows(testName, collectorOutput.bad)
        } yield {
          statusBeforeCreate should beEqualTo(Status.ServiceUnavailable)
          statusAfterCreate should beEqualTo(Status.Ok)
          collectorOutput.good.size should beEqualTo(nbGood)
          collectorOutput.bad.size should beEqualTo(nbBad)
        }
      }
    }
  }
}
