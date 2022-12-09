/*
 * Copyright (c) 2013-2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.collectors.scalastream.integration

import cats.effect.{IO, Resource, Sync}
import cats.effect.testing.specs2.CatsIO
import cats.implicits.toTraverseOps
import com.snowplowanalytics.snowplow.collectors.scalastream.integration.SetCookieSpec.Cookie
import com.snowplowanalytics.snowplow.collectors.scalastream.integration.utils._
import com.snowplowanalytics.snowplow.collectors.scalastream.integration.utils.Http.Request.RequestType.Good
import com.snowplowanalytics.snowplow.collectors.scalastream.integration.utils.Http.sendOne
import com.snowplowanalytics.snowplow.eventgen.tracker.{HttpRequest => RequestStub}
import org.specs2.matcher.MatchResult
import org.specs2.matcher.Matchers.{beCloseTo, beTrue, combineMatchResult}
import org.specs2.matcher.MustMatchers.theValue
import org.specs2.mutable.Specification
import org.testcontainers.containers.{GenericContainer => JGenericContainer}

import java.net.http.HttpClient
import scala.compat.java8.OptionConverters.RichOptionalGeneric
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class SetCookieSpec extends Specification with CatsIO {
  "The collector" should {
    val requestStubs = EventGenerator.makeStubs(10, 50)

    def resources(collector: JGenericContainer[_]): Resource[IO, (JGenericContainer[_], HttpClient)] =
      for {
        collector  <- Containers.mkContainer[IO](collector)
        httpClient <- Http.mkHttpClient[IO]
      } yield (collector, httpClient)

    "respond with a `Set-Cookie` header if the request has no `SP-Anonymous:*` header, and with none otherwise" in {

      /**
        * @param f allows us to modify the request and inject specific values
        */
      def run(testConfig: CollectorConfig, f: RequestStub => RequestStub, expectedCookie: Cookie) = {
        val collector = Containers.collector("stdout", testConfig)

        resources(collector).use {
          case (collector, httpClient) =>
            val collectorPort = Containers.getExposedPort(collector, Containers.CollectorExposedPort)
            val good          = requestStubs.map(f).map(Http.Request.make(_, collectorPort, Good))

            for {
              responses <- good.traverse(sendOne[IO](_, httpClient))
              results <- Sync[IO].delay(responses.zip(good).map {
                case (resp, req) =>
                  (resp.headers.firstValue("Set-Cookie").asScala, req.headers.firstValue("SP-Anonymous").asScala)
              })
            } yield results.map {
              case (cookieHeader, anonymousHeader) =>
                if (anonymousHeader.isDefined) cookieHeader must beNone
                else (cookieHeader must beSome).and(expectedCookie.mustMatchString(cookieHeader.getOrElse("")))
            }
        }
      }

      def noOp: RequestStub => RequestStub = identity

      "(1) Secure: true, HttpOnly: true, SameSite: Strict, no cookie domains, no fallback domain" in {
        val testConfig = Map(
          "COLLECTOR_COOKIE_ENABLED"    -> "true",
          "COLLECTOR_COOKIE_EXPIRATION" -> "365 days",
          "COLLECTOR_COOKIE_NAME"       -> "sp",
          "COLLECTOR_COOKIE_SECURE"     -> "true",
          "COLLECTOR_COOKIE_HTTP_ONLY"  -> "true",
          "COLLECTOR_COOKIE_SAME_SITE"  -> "Strict"
        )

        val expectedCookie = Cookie("sp", 365.days, secure = true, httpOnly = true, "Strict", "")

        run(testConfig, noOp, expectedCookie)
      }

      "(2) Secure: false, HttpOnly: false, SameSite: not configured, no cookie domains, no fallback domain" in {
        val testConfig = Map(
          "COLLECTOR_COOKIE_ENABLED"    -> "true",
          "COLLECTOR_COOKIE_EXPIRATION" -> "7 days",
          "COLLECTOR_COOKIE_NAME"       -> "alt",
          "COLLECTOR_COOKIE_SECURE"     -> "false",
          "COLLECTOR_COOKIE_HTTP_ONLY"  -> "false"
        )

        val expectedCookie = Cookie("alt", 7.days, secure = false, httpOnly = false, "None", "")

        run(testConfig, noOp, expectedCookie)
      }

      "(3) Secure: false, HttpOnly: false, SameSite: Lax, no cookie domains, fallback domain configured" in {
        val testConfig = Map(
          "COLLECTOR_COOKIE_ENABLED"         -> "true",
          "COLLECTOR_COOKIE_EXPIRATION"      -> "365 days",
          "COLLECTOR_COOKIE_NAME"            -> "sp",
          "COLLECTOR_COOKIE_SECURE"          -> "false",
          "COLLECTOR_COOKIE_HTTP_ONLY"       -> "false",
          "COLLECTOR_COOKIE_SAME_SITE"       -> "Lax",
          "COLLECTOR_COOKIE_FALLBACK_DOMAIN" -> "test.co.uk"
        )

        val expectedCookie = Cookie("sp", 365.days, secure = false, httpOnly = false, "Lax", "test.co.uk")

        run(testConfig, noOp, expectedCookie)
      }

      "(4) Secure: false, HttpOnly: false, SameSite: not configured, cookie domains configured, no fallback domain configured" in {
        val testConfig = Map(
          "COLLECTOR_COOKIE_ENABLED"    -> "true",
          "COLLECTOR_COOKIE_EXPIRATION" -> "365 days",
          "COLLECTOR_COOKIE_NAME"       -> "sp",
          "COLLECTOR_COOKIE_SECURE"     -> "false",
          "COLLECTOR_COOKIE_HTTP_ONLY"  -> "false",
          "COLLECTOR_COOKIE_DOMAIN_1"   -> "test.com",
          "COLLECTOR_COOKIE_DOMAIN_2"   -> "test.net",
          "COLLECTOR_COOKIE_DOMAIN_3"   -> "test.info"
        )

        "(a) if an Origin domain matches one of the configured cookie domains" in {
          val expectedCookie = Cookie("sp", 365.days, secure = false, httpOnly = false, "None", "test.net")

          run(testConfig, Http.Request.addOrigin(_, "http://test.net"), expectedCookie)
        }

        "(b) if no Origin domain matches one of the configured cookie domains" in {
          val expectedCookie = Cookie("sp", 365.days, secure = false, httpOnly = false, "None", "")

          run(testConfig, noOp, expectedCookie)
        }
      }

      "(5) Secure: false, HttpOnly: false, SameSite: not configured, cookie domains configured, fallback domain configured" in {
        val testConfig = Map(
          "COLLECTOR_COOKIE_ENABLED"         -> "true",
          "COLLECTOR_COOKIE_EXPIRATION"      -> "365 days",
          "COLLECTOR_COOKIE_NAME"            -> "sp",
          "COLLECTOR_COOKIE_SECURE"          -> "false",
          "COLLECTOR_COOKIE_HTTP_ONLY"       -> "false",
          "COLLECTOR_COOKIE_DOMAIN_1"        -> "test.com",
          "COLLECTOR_COOKIE_DOMAIN_2"        -> "test.net",
          "COLLECTOR_COOKIE_DOMAIN_3"        -> "test.info",
          "COLLECTOR_COOKIE_FALLBACK_DOMAIN" -> "test.co.uk"
        )

        "(a) if an Origin domain matches one of the configured cookie domains" in {
          val expectedCookie = Cookie("sp", 365.days, secure = false, httpOnly = false, "None", "test.info")

          run(testConfig, Http.Request.addOrigin(_, "http://test.info"), expectedCookie)
        }

        "(b) if no Origin domain matches one of the configured cookie domains" in {
          val expectedCookie = Cookie("sp", 365.days, secure = false, httpOnly = false, "None", "test.co.uk")

          run(testConfig, noOp, expectedCookie)
        }
      }
    }

    "not send back a `Set-Cookie` header if cookies are disabled in the config" in {
      val testConfig = Map("COLLECTOR_COOKIE_ENABLED" -> "false")
      val collector  = Containers.collector("stdout", testConfig)

      resources(collector).use {
        case (collector, httpClient) =>
          val collectorPort = Containers.getExposedPort(collector, Containers.CollectorExposedPort)
          val good          = requestStubs.map(Http.Request.make(_, collectorPort, Good))

          for {
            responses <- good.traverse(sendOne[IO](_, httpClient))
            results   <- Sync[IO].delay(responses.flatMap(_.headers().firstValue("Set-Cookie").asScala))
          } yield results mustEqual List.empty
      }
    }

    "send back a `Set-Cookie` header even when doNotTrackCookie is enabled, if the dnt cookie is not part of the request" in {
      val testConfig = Map(
        "COLLECTOR_COOKIE_ENABLED"              -> "true",
        "COLLECTOR_COOKIE_EXPIRATION"           -> "365 days",
        "COLLECTOR_COOKIE_NAME"                 -> "sp",
        "COLLECTOR_COOKIE_SECURE"               -> "false",
        "COLLECTOR_COOKIE_HTTP_ONLY"            -> "false",
        "COLLECTOR_DO_NOT_TRACK_COOKIE_ENABLED" -> "true",
        "COLLECTOR_DO_NOT_TRACK_COOKIE_NAME"    -> "dnt",
        "COLLECTOR_DO_NOT_TRACK_COOKIE_VALUE"   -> "dnt"
      )

      val collector = Containers.collector("stdout", testConfig)

      resources(collector).use {
        case (collector, httpClient) =>
          val collectorPort = Containers.getExposedPort(collector, Containers.CollectorExposedPort)
          // EventGenerator.makeStubs does not add a dnt cookie
          val good         = requestStubs.map(Http.Request.make(_, collectorPort, Good))
          val nonAnonymous = good.filter(req => req.headers.firstValue("SP-Anonymous").asScala.isEmpty)

          for {
            responses <- good.traverse(sendOne[IO](_, httpClient))
            results   <- Sync[IO].delay(responses.flatMap(_.headers().firstValue("Set-Cookie").asScala))
          } yield results.size mustEqual nonAnonymous.size
      }
    }

    "not send back a `Set-Cookie` header if doNotTrackCookie is enabled and the request contains a dnt cookie" in {
      val testConfig = Map(
        "COLLECTOR_COOKIE_ENABLED"              -> "true",
        "COLLECTOR_COOKIE_EXPIRATION"           -> "365 days",
        "COLLECTOR_COOKIE_NAME"                 -> "sp",
        "COLLECTOR_COOKIE_SECURE"               -> "false",
        "COLLECTOR_COOKIE_HTTP_ONLY"            -> "false",
        "COLLECTOR_DO_NOT_TRACK_COOKIE_ENABLED" -> "true",
        "COLLECTOR_DO_NOT_TRACK_COOKIE_NAME"    -> "dnt",
        "COLLECTOR_DO_NOT_TRACK_COOKIE_VALUE"   -> "dnt"
      )

      val collector = Containers.collector("stdout", testConfig)

      resources(collector).use {
        case (collector, httpClient) =>
          val collectorPort = Containers.getExposedPort(collector, Containers.CollectorExposedPort)
          val good          = requestStubs.map(Http.Request.addDNT).map(Http.Request.make(_, collectorPort, Good))

          for {
            responses <- good.traverse(sendOne[IO](_, httpClient))
            results   <- Sync[IO].delay(responses.flatMap(_.headers().firstValue("Set-Cookie").asScala))
          } yield results mustEqual List.empty
      }
    }
  }
}

object SetCookieSpec {
  case class Cookie(
    name: String,
    expiration: FiniteDuration,
    secure: Boolean,
    httpOnly: Boolean,
    sameSite: String,
    domain: String
  ) {
    def mustMatchString(otherCookie: String): MatchResult[Any] = {
      val otherAsMap = otherCookie.split("; ").toList.map(_.split("=").toList).foldLeft(Map.empty[String, String]) {
        case (acc, h :: Nil) => acc ++ Map(h -> "")
        case (acc, h :: t)   => acc ++ Map(h -> t.head)
        case (acc, Nil)      => acc
      }

      (otherAsMap.contains(this.name) must beTrue)
        .and(
          (Epoch.fromString(otherAsMap.getOrElse("Expires", "")) - Epoch.now) must beCloseTo(
            this.expiration.toMillis,
            5000L
          )
        )
        .and(otherAsMap.contains("Secure") mustEqual this.secure)
        .and(otherAsMap.contains("HttpOnly") mustEqual this.httpOnly)
        .and(otherAsMap.getOrElse("SameSite", "None") mustEqual this.sameSite) // None by default because of the reference.conf in core
        .and(otherAsMap.getOrElse("Domain", "") mustEqual this.domain)
    }
  }
}
