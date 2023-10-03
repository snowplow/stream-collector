/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Snowplow Community License Version 1.0,
 * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
 * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
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
          body must beEqualTo("User-agent: *\nDisallow: /")
          collectorOutput.good must beEmpty
          collectorOutput.bad must beEmpty
        }
      }
    }
  }
}
