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
package com.snowplowanalytics.snowplow.collectors.scalastream.it.core

import scala.concurrent.duration._

import org.specs2.mutable.Specification

import cats.effect.IO

import org.http4s.{Method, Request, Uri}

import cats.effect.testing.specs2.CatsEffect

import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.Kinesis
import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.containers._
import com.snowplowanalytics.snowplow.collectors.scalastream.it.Http

class RobotsSpec extends Specification with Localstack with CatsEffect {

  override protected val Timeout = 5.minutes

  "collector" should {
    "respond to /robots.txt with 200 and not emit any event" in {
      val testName = "robots"
      val streamGood = s"$testName-raw"
      val streamBad = s"$testName-bad-1"

      Collector.container(
        "kinesis/src/it/resources/collector.hocon",
        testName,
        streamGood,
        streamBad
      ).use { collector =>
        val uri = Uri.unsafeFromString(s"http://${collector.host}:${collector.port}/robots.txt")
        val request = Request[IO](Method.GET, uri)

        for {
          response <- Http.response(request)
          bodyBytes <- response.body.compile.toList
          body = new String(bodyBytes.toArray)
          _ <- IO.sleep(10.second)
          collectorOutput <- Kinesis.readOutput(streamGood, streamBad)
        } yield {
          response.status.code must beEqualTo(200)
          body must beEqualTo("User-agent: *\nDisallow: /\n\nUser-agent: Googlebot\nDisallow: /\n\nUser-agent: AdsBot-Google\nDisallow: /")
          collectorOutput.good must beEmpty
          collectorOutput.bad must beEmpty
        }
      }
    }
  }
}
