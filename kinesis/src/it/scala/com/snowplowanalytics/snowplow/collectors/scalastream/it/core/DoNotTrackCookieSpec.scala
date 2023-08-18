/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Snowplow Community License Version 1.0,
 * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
 * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
 */
package com.snowplowanalytics.snowplow.collectors.scalastream.it.core

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import com.snowplowanalytics.snowplow.collectors.scalastream.it.{EventGenerator, Http}
import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.Kinesis
import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.containers._
import org.specs2.execute.PendingUntilFixed
import org.specs2.mutable.Specification

import scala.collection.JavaConverters._
import scala.concurrent.duration._

class DoNotTrackCookieSpec extends Specification with Localstack with CatsEffect with PendingUntilFixed {

  override protected val Timeout = 5.minutes

  "collector" should {
    val cookieName = "foo"
    val cookieValue = "bar"

    "ignore events that have a cookie whose name and value match doNotTrackCookie config if enabled" in {
      import cats.effect.unsafe.implicits.global
      
      val testName = "doNotTrackCookie-enabled"
      val streamGood = s"$testName-raw"
      val streamBad = s"$testName-bad-1"

      Collector.container(
        "kinesis/src/it/resources/collector-doNotTrackCookie-enabled.hocon",
        testName,
        streamGood,
        streamBad
      ).use { collector =>
        val requests = List(
          EventGenerator.mkTp2Event(collector.host, collector.port).addCookie(cookieName, cookieName),
          EventGenerator.mkTp2Event(collector.host, collector.port).addCookie(cookieValue, cookieValue),
          EventGenerator.mkTp2Event(collector.host, collector.port).addCookie(cookieName, cookieValue)
        )

        val expected = List(s"Cookie: $cookieName=$cookieName", s"Cookie: $cookieValue=$cookieValue")

        for {
          statuses <- Http.statuses(requests)
          _ <- IO.sleep(5.second)
          collectorOutput <- Kinesis.readOutput(streamGood, streamBad)
          headers = collectorOutput.good.map(_.headers.asScala)
        } yield {
          statuses.map(_.code) must beEqualTo(List(200, 200, 200))
          headers must haveSize(2)
          expected.forall(cookie => headers.exists(_.contains(cookie))) must beTrue
        }
      }.unsafeRunSync()
    }.pendingUntilFixed("Remove when 'do not track cookie' feature is implemented")
    
    "track events that have a cookie whose name and value match doNotTrackCookie config if disabled" in { 
      val testName = "doNotTrackCookie-disabled"
      val streamGood = s"$testName-raw"
      val streamBad = s"$testName-bad-1"

      Collector.container(
        "kinesis/src/it/resources/collector-doNotTrackCookie-disabled.hocon",
        testName,
        streamGood,
        streamBad
      ).use { collector =>
        val request = EventGenerator.mkTp2Event(collector.host, collector.port).addCookie(cookieName, cookieValue)

        val expected = s"Cookie: $cookieName=$cookieValue"

        for {
          status <- Http.status(request)
          _ <- IO.sleep(5.second)
          collectorOutput <- Kinesis.readOutput(streamGood, streamBad)
          headers = collectorOutput.good.map(_.headers.asScala)
        } yield {
          status.code must beEqualTo(200)
          headers match {
            case List(one) if one.contains(expected) => ok
            case other =>
              ko(s"$other is not one list that contains [$expected]")
          }
        }
      }
    }
  }
}
