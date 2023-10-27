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
import com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.containers._
import org.http4s.{Header, SameSite}
import org.specs2.mutable.Specification
import org.typelevel.ci.CIStringSyntax

import scala.concurrent.duration._

class CookieSpec extends Specification with Localstack with CatsEffect {

  override protected val Timeout = 5.minutes

  "collector" should {
    "set cookie attributes according to configuration" in {
      "name, expiration, secure true, httpOnly true, SameSite" in {
        val testName = "cookie-attributes-1"
        val streamGood = s"$testName-raw"
        val streamBad = s"$testName-bad-1"

        Collector.container(
          "kinesis/src/it/resources/collector-cookie-attributes-1.hocon",
          testName,
          streamGood,
          streamBad
        ).use { collector =>
          val request = EventGenerator.mkTp2Event(collector.host, collector.port)

          for {
            resp <- Http.response(request)
            now <- IO.realTime
          } yield {
            resp.cookies match {
              case List(cookie) =>
                cookie.name must beEqualTo("greatName")
                cookie.expires match {
                  case Some(expiry) =>
                    expiry.epochSecond should beCloseTo((now + 42.days).toSeconds, 10)
                  case None =>
                    ko(s"Cookie [$cookie] doesn't contain the expiry date")
                }
                cookie.secure should beTrue
                cookie.httpOnly should beTrue
                cookie.sameSite should beSome(SameSite.Strict)
              case _ => ko(s"There is not 1 cookie but ${resp.cookies.size}")
            }
          }
        }
      }

      "secure false, httpOnly false" in {
        val testName = "cookie-attributes-2"
        val streamGood = s"$testName-raw"
        val streamBad = s"$testName-bad-1"

        Collector.container(
          "kinesis/src/it/resources/collector-cookie-attributes-2.hocon",
          testName,
          streamGood,
          streamBad
        ).use { collector =>
          val request = EventGenerator.mkTp2Event(collector.host, collector.port)

          for {
            resp <- Http.response(request)
          } yield {
            resp.cookies match {
              case List(cookie) =>
                cookie.secure should beTrue 
                cookie.httpOnly should beFalse
              case _ => ko(s"There is not 1 cookie but ${resp.cookies.size}")
            }
          }
        }
      }
    }

    "not set cookie if the request sets SP-Anonymous header" in {
      val testName = "cookie-anonymous"
      val streamGood = s"$testName-raw"
      val streamBad = s"$testName-bad-1"

      Collector.container(
        "kinesis/src/it/resources/collector-cookie-anonymous.hocon",
        testName,
        streamGood,
        streamBad
      ).use { collector =>
        val request = EventGenerator.mkTp2Event(collector.host, collector.port)
          .withHeaders(Header.Raw(ci"SP-Anonymous", "*"))

        for {
          resp <- Http.response(request)
        } yield {
          resp.cookies should beEmpty
        }
      }
    }

    "not set the domain property of the cookie if collector.cookie.domains and collector.cookie.fallbackDomain are empty" in {
      val testName = "cookie-no-domain"
      val streamGood = s"$testName-raw"
      val streamBad = s"$testName-bad-1"

      Collector.container(
        "kinesis/src/it/resources/collector-cookie-no-domain.hocon",
        testName,
        streamGood,
        streamBad
      ).use { collector =>
        val request = EventGenerator.mkTp2Event(collector.host, collector.port)
          .withHeaders(Header.Raw(ci"Origin", "http://my.domain"))

        for {
          resp <- Http.response(request)
        } yield {
          resp.cookies.map(cookie => cookie.domain should beNone)
        }
      }
    }

    "set the domain property of the cookie to the first domain of collector.cookie.domains that matches Origin, even with fallbackDomain enabled" in {
      val testName = "cookie-domain"
      val streamGood = s"$testName-raw"
      val streamBad = s"$testName-bad-1"

      Collector.container(
        "kinesis/src/it/resources/collector-cookie-domain.hocon",
        testName,
        streamGood,
        streamBad
      ).use { collector =>
        val request = EventGenerator.mkTp2Event(collector.host, collector.port)
          .withHeaders(Header.Raw(ci"Origin", "http://sub.foo.bar"))

        for {
          resp <- Http.response(request)
        } yield {
          resp.cookies match {
            case List(cookie) =>
              cookie.domain should beSome("foo.bar")
            case _ => ko(s"There is not 1 cookie but ${resp.cookies.size}")
          }
        }
      }
    }

    "set the domain property of the cookie to collector.cookie.fallbackDomain if there is no Origin header in the request or if it contains no host that is in collector.cookie.domains" in {
      val testName = "cookie-fallback"
      val streamGood = s"$testName-raw"
      val streamBad = s"$testName-bad-1"

      Collector.container(
        "kinesis/src/it/resources/collector-cookie-fallback.hocon",
        testName,
        streamGood,
        streamBad
      ).use { collector =>
        val request1 = EventGenerator.mkTp2Event(collector.host, collector.port)
          .withHeaders(Header.Raw(ci"Origin", s"http://other.domain"))
        val request2 = EventGenerator.mkTp2Event(collector.host, collector.port)

        for {
          responses <- Http.responses(List(request1, request2))
        } yield {
          responses.flatMap(r => r.cookies.map( c => c.domain must beSome("fallback.domain")))
        }
      }
    }
  }
}
