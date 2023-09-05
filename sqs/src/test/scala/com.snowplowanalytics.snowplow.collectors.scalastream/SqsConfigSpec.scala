/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
  */
package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{ExitCode, IO}
import com.snowplowanalytics.snowplow.collector.core.{Config, ConfigParser}
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.SqsSinkConfig
import org.http4s.SameSite
import org.specs2.mutable.Specification

import java.nio.file.Paths
import scala.concurrent.duration.DurationInt

class SqsConfigSpec extends Specification with CatsEffect {

  "Config parser" should {
    "be able to parse extended kinesis config" in {
      assert(
        resource       = "/config.sqs.extended.hocon",
        expectedResult = Right(SqsConfigSpec.expectedConfig)
      )
    }
    "be able to parse minimal kinesis config" in {
      assert(
        resource       = "/config.sqs.minimal.hocon",
        expectedResult = Right(SqsConfigSpec.expectedConfig)
      )
    }
  }

  private def assert(resource: String, expectedResult: Either[ExitCode, Config[SqsSinkConfig]]) = {
    val path = Paths.get(getClass.getResource(resource).toURI)
    ConfigParser.fromPath[IO, SqsSinkConfig](Some(path)).value.map { result =>
      result must beEqualTo(expectedResult)
    }
  }
}

object SqsConfigSpec {

  private val expectedConfig = Config[SqsSinkConfig](
    interface = "0.0.0.0",
    port      = 8080,
    paths     = Map.empty[String, String],
    p3p = Config.P3P(
      policyRef = "/w3c/p3p.xml",
      CP        = "NOI DSP COR NID PSA OUR IND COM NAV STA"
    ),
    crossDomain = Config.CrossDomain(
      enabled = false,
      domains = List("*"),
      secure  = true
    ),
    cookie = Config.Cookie(
      enabled        = true,
      expiration     = 365.days,
      name           = "sp",
      domains        = List.empty,
      fallbackDomain = None,
      secure         = true,
      httpOnly       = true,
      sameSite       = Some(SameSite.None)
    ),
    doNotTrackCookie = Config.DoNotTrackCookie(
      enabled = false,
      name    = "",
      value   = ""
    ),
    cookieBounce = Config.CookieBounce(
      enabled                 = false,
      name                    = "n3pc",
      fallbackNetworkUserId   = "00000000-0000-4000-A000-000000000000",
      forwardedProtocolHeader = None
    ),
    redirectMacro = Config.RedirectMacro(
      enabled     = false,
      placeholder = None
    ),
    rootResponse = Config.RootResponse(
      enabled    = false,
      statusCode = 302,
      headers    = Map.empty[String, String],
      body       = ""
    ),
    cors = Config.CORS(1.hour),
    monitoring =
      Config.Monitoring(Config.Metrics(Config.Statsd(false, "localhost", 8125, 10.seconds, "snowplow.collector"))),
    ssl                   = Config.SSL(enable = false, redirect = false, port = 443),
    enableDefaultRedirect = false,
    redirectDomains       = Set.empty,
    preTerminationPeriod  = 10.seconds,
    streams = Config.Streams(
      good                       = "good",
      bad                        = "bad",
      useIpAddressAsPartitionKey = false,
      buffer = Config.Buffer(
        byteLimit   = 3145728,
        recordLimit = 500,
        timeLimit   = 5000
      ),
      sink = SqsSinkConfig(
        maxBytes = 192000,
        region   = "eu-central-1",
        backoffPolicy = SqsSinkConfig.BackoffPolicyConfig(
          minBackoff = 500,
          maxBackoff = 1500,
          maxRetries = 3
        ),
        threadPoolSize = 10
      )
    )
  )

}
