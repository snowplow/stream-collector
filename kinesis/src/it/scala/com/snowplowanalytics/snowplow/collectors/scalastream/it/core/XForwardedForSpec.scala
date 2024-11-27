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
package com.snowplowanalytics.snowplow.collectors.scalastream.it.core

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import com.comcast.ip4s.IpAddress
import com.snowplowanalytics.snowplow.collectors.scalastream.it.{EventGenerator, Http}
import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.Kinesis
import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.containers._
import org.http4s.headers.`X-Forwarded-For`
import org.specs2.mutable.Specification

import scala.concurrent.duration._

class XForwardedForSpec extends Specification with Localstack with CatsEffect {

  override protected val Timeout = 5.minutes

  "collector" should {
    "put X-Forwarded-For header in the collector payload" in {
      val testName = "X-Forwarded-For"
      val streamGood = s"$testName-raw"
      val streamBad = s"$testName-bad-1"

      val ip = IpAddress.fromString("123.123.123.123")

      Collector.container(
        "kinesis/src/it/resources/collector.hocon",
        testName,
        streamGood,
        streamBad
      ).use { collector =>
        val request = EventGenerator.mkTp2Event(collector.host, collector.port)
          .withHeaders(`X-Forwarded-For`(NonEmptyList.one(ip)))

        for {
          _ <- Http.status(request)
          _ <- IO.sleep(5.second)
          collectorOutput <- Kinesis.readOutput(streamGood, streamBad)
        } yield {
          val expected = "X-Forwarded-For: 123.123.123.123"
          collectorOutput.good match {
            case List(one) if one.headers.contains(expected) => ok
            case List(one) => ko(s"${one.headers} doesn't contain $expected")
            case other => ko(s"${other.size} output collector payload instead of one")
          }
        }
      }
    }
  }
}
