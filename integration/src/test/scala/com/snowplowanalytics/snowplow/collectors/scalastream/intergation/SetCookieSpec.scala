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
package com.snowplowanalytics.snowplow.collectors.scalastream.intergation

import cats.effect.{IO, Resource, Sync}
import cats.effect.testing.specs2.CatsIO
import cats.implicits.toTraverseOps
import com.snowplowanalytics.snowplow.collectors.scalastream.intergation.SetCookieSpec.Cookie
import com.snowplowanalytics.snowplow.collectors.scalastream.intergation.TestUtils.Http.Request.RequestType.Good
import com.snowplowanalytics.snowplow.collectors.scalastream.intergation.TestUtils.Http.sendOne
import com.snowplowanalytics.snowplow.collectors.scalastream.intergation.TestUtils.{Epoch, EventGenerator, Http}
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
      def run(config: String, f: RequestStub => RequestStub, expectedCookie: Cookie) = {
        val collector = Containers.collector("stdout", config)

        resources(collector).use {
          case (collector, httpClient) =>
            val collectorPort = Containers.getExposedPort(collector, 12345)
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

      val expectedCookie1  = Cookie("sp", 365.days, secure = true, httpOnly  = true, "Strict", "")
      val expectedCookie2  = Cookie("alt", 7.days, secure  = false, httpOnly = false, "None", "")
      val expectedCookie3  = Cookie("sp", 365.days, secure = false, httpOnly = false, "Lax", "test.co.uk")
      val expectedCookie4a = Cookie("sp", 365.days, secure = false, httpOnly = false, "None", "test.net")
      val expectedCookie4b = Cookie("sp", 365.days, secure = false, httpOnly = false, "None", "")
      val expectedCookie5a = Cookie("sp", 365.days, secure = false, httpOnly = false, "None", "test.info")
      val expectedCookie5b = Cookie("sp", 365.days, secure = false, httpOnly = false, "None", "test.co.uk")

      run("config", noOp, expectedCookie1)
      run("config-alt", noOp, expectedCookie2)
      run("fallback-domain-only", noOp, expectedCookie3)
      run("domains-only", Http.Request.addOrigin(_, "test.net"), expectedCookie4a)
      run("domains-only", noOp, expectedCookie4b)
      run("domains-and-fallback-domain", Http.Request.addOrigin(_, "test.info"), expectedCookie5a)
      run("domains-and-fallback-domain", noOp, expectedCookie5b)
    }

    "not send back a `Set-Cookie` header if cookies are disabled in the config" in {
      val collector = Containers.collector("stdout", "cookies-disabled")

      resources(collector).use {
        case (collector, httpClient) =>
          val collectorPort = Containers.getExposedPort(collector, 12345)
          val good          = requestStubs.map(Http.Request.make(_, collectorPort, Good))

          for {
            responses <- good.traverse(sendOne[IO](_, httpClient))
            results   <- Sync[IO].delay(responses.flatMap(_.headers().firstValue("Set-Cookie").asScala))
          } yield results mustEqual List.empty
      }
    }

    "send back a `Set-Cookie` header even when doNotTrackCookie is enabled, if the dnt cookie is not part of the request" in {
      val collector = Containers.collector("stdout", "do-not-track")

      resources(collector).use {
        case (collector, httpClient) =>
          val collectorPort = Containers.getExposedPort(collector, 12345)
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
      val collector = Containers.collector("stdout", "do-not-track")

      resources(collector).use {
        case (collector, httpClient) =>
          val collectorPort = Containers.getExposedPort(collector, 12345)
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
