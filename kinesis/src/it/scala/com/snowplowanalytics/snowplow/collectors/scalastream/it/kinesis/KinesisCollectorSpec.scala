/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.0
 * located at https://docs.snowplow.io/limited-use-license-1.1
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.containers._
import com.snowplowanalytics.snowplow.collectors.scalastream.it.utils._
import com.snowplowanalytics.snowplow.collectors.scalastream.it.{EventGenerator, Http}
import org.http4s.{Method, Request, Status, Uri}
import org.specs2.mutable.Specification
import org.testcontainers.containers.GenericContainer

import scala.concurrent.duration._

class KinesisCollectorSpec extends Specification with Localstack with CatsEffect {

  override protected val Timeout = 5.minutes

  val stopTimeout = 20.second

  "collector-kinesis" should {
    "be able to parse the minimal config" in {
      val testName = "minimal"
      Collector.container(
        "examples/config.kinesis.minimal.hocon",
        testName,
        s"$testName-raw",
        s"$testName-bad-1"
      ).use { collector =>
        IO(collector.container.getLogs() must contain(("Service bound to address")))
      }
    }

    "emit the correct number of collector payloads and bad rows" in {
      val testName = "count"
      val nbGood = 1000
      val nbBad = 10
      val streamGood = s"$testName-raw"
      val streamBad = s"$testName-bad-1"

      Collector.container(
        "kinesis/src/it/resources/collector.hocon",
        testName,
        streamGood,
        streamBad
      ).use { collector =>
        for {
          _ <- log(testName, "Sending data")
          _ <- EventGenerator.sendEvents(
            collector.host,
            collector.port,
            nbGood,
            nbBad,
            Collector.maxBytes
          )
          _ <- log(testName, "Data sent. Waiting for collector to work")
          _ <- IO.sleep(5.second)
          _ <- log(testName, "Consuming collector's output")
          collectorOutput <- Kinesis.readOutput(streamGood, streamBad)
          _ <- printBadRows(testName, collectorOutput.bad)
        } yield {
          collectorOutput.good.size should beEqualTo(nbGood)
          collectorOutput.bad.size should beEqualTo(nbBad)
        }
      }
    }

    s"shutdown within $stopTimeout when it receives a SIGTERM" in {
      val testName = "stop"
      Collector.container(
        "kinesis/src/it/resources/collector.hocon",
        testName,
        s"$testName-raw",
        s"$testName-bad-1"
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

    "start with /sink-health unhealthy and insert pending events when streams become available" in {
      val testName = "sink-health"
      val nbGood = 10
      val nbBad = 10
      val streamGood = s"$testName-raw"
      val streamBad = s"$testName-bad-1"

      Collector.container(
        "kinesis/src/it/resources/collector.hocon",
        testName,
        streamGood,
        streamBad,
        createStreams = false
      ).use { collector =>
        val uri = Uri.unsafeFromString(s"http://${collector.host}:${collector.port}/sink-health")
        val request = Request[IO](Method.GET, uri)

        for {
          statusBeforeCreate <- Http.status(request)
          _ <- EventGenerator.sendEvents(
            collector.host,
            collector.port,
            nbGood,
            nbBad,
            Collector.maxBytes
          )
          _ <- Localstack.createStreams(List(streamGood, streamBad))
          _ <- IO.sleep(10.second)
          statusAfterCreate <- Http.status(request)
          collectorOutput <- Kinesis.readOutput(streamGood, streamBad)
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
