/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.0
 * located at https://docs.snowplow.io/limited-use-license-1.0
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream.it.core

import scala.concurrent.duration._

import cats.effect.IO

import cats.effect.testing.specs2.CatsIO

import org.specs2.mutable.Specification

import org.http4s.{Request, Method, Uri}

import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.containers._
import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.Kinesis
import com.snowplowanalytics.snowplow.collectors.scalastream.it.Http

class HealthEndpointSpec extends Specification with Localstack with CatsIO {
  
  override protected val Timeout = 5.minutes

  "collector" should {
    "respond with 200 to /health endpoint after it has started" in {
      val testName = "health-endpoint"
      val streamGood = s"${testName}-raw"
      val streamBad = s"${testName}-bad-1"
      Collector.container(
        "kinesis/src/it/resources/collector.hocon",
        testName,
        streamGood,
        streamBad
      ).use { collector =>
        val uri = Uri.unsafeFromString(s"http://${collector.host}:${collector.port}/health")
        val request = Request[IO](Method.GET, uri)

        for {
          status <- Http.status(request)
          _ <- IO.sleep(5.second)
          collectorOutput <- Kinesis.readOutput(streamGood, streamBad)
        } yield {
          status.code must beEqualTo(200)
          collectorOutput.good.size should beEqualTo(0)
          collectorOutput.bad.size should beEqualTo(0)
        }
      }
    }
  }
}
