/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Snowplow Community License Version 1.0,
 * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
 * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
 */
package com.snowplowanalytics.snowplow.collectors.scalastream.it.core

import java.net.InetAddress

import scala.concurrent.duration._

import cats.data.NonEmptyList

import cats.effect.IO

import cats.effect.testing.specs2.CatsIO

import org.specs2.mutable.Specification

import org.http4s.headers.`X-Forwarded-For`

import com.snowplowanalytics.snowplow.collectors.scalastream.it.Http
import com.snowplowanalytics.snowplow.collectors.scalastream.it.EventGenerator

import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.containers._
import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.Kinesis

class XForwardedForSpec extends Specification with Localstack with CatsIO {

  override protected val Timeout = 5.minutes

  "collector" should {
    "put X-Forwarded-For header in the collector payload" in {
      val testName = "X-Forwarded-For"
      val streamGood = s"${testName}-raw"
      val streamBad = s"${testName}-bad-1"

      val ip = InetAddress.getByName("123.123.123.123")

      Collector.container(
        "kinesis/src/it/resources/collector.hocon",
        testName,
        streamGood,
        streamBad
      ).use { collector =>
        val request = EventGenerator.mkTp2Event(collector.host, collector.port)
          .withHeaders(`X-Forwarded-For`(NonEmptyList.one(Some(ip))))

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
