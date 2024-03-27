/*
 * Copyright (c) 2012-present Snowplow Analytics Ltd. All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.0
 * located at https://docs.snowplow.io/limited-use-license-1.0
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect.testing.specs2.CatsEffect
import cats.effect.{ExitCode, IO}
import com.snowplowanalytics.snowplow.collector.core.{Config, ConfigParser}
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.PubSubSinkConfig
import org.http4s.SameSite
import org.specs2.mutable.Specification

import java.nio.file.Paths
import scala.concurrent.duration.DurationInt

class ConfigSpec extends Specification with CatsEffect {

  "Config parser" should {
    "be able to parse extended pubsub config" in {
      assert(
        resource = "/config.pubsub.extended.hocon",
        expectedResult = Right(
          ConfigSpec
            .expectedConfig
            .copy(
              monitoring = Config.Monitoring(
                Config.Metrics(
                  ConfigSpec.expectedConfig.monitoring.metrics.statsd.copy(tags = Map("app" -> "collector"))
                )
              )
            )
        )
      )
    }
    "be able to parse minimal pubsub config" in {
      assert(
        resource       = "/config.pubsub.minimal.hocon",
        expectedResult = Right(ConfigSpec.expectedConfig)
      )
    }
  }

  private def assert(resource: String, expectedResult: Either[ExitCode, Config[PubSubSinkConfig]]) = {
    val path = Paths.get(getClass.getResource(resource).toURI)
    ConfigParser.fromPath[IO, PubSubSinkConfig](Some(path)).value.map { result =>
      result must beEqualTo(expectedResult)
    }
  }
}

object ConfigSpec {

  private val expectedConfig = Config[PubSubSinkConfig](
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
    monitoring = Config.Monitoring(
      Config.Metrics(
        Config.Statsd(false, "localhost", 8125, 10.seconds, "snowplow.collector", Map.empty)
      )
    ),
    ssl                   = Config.SSL(enable = false, redirect = false, port = 443),
    hsts                  = Config.HSTS(enable = false, maxAge = 365.days),
    enableDefaultRedirect = false,
    redirectDomains       = Set.empty,
    preTerminationPeriod  = 10.seconds,
    networking = Config.Networking(
      maxConnections        = 1024,
      idleTimeout           = 610.seconds,
      responseHeaderTimeout = 2.seconds,
      bodyReadTimeout       = 500.millis
    ),
    streams = Config.Streams(
      useIpAddressAsPartitionKey = false,
      good = Config.Sink(
        name = "good",
        buffer = Config.Buffer(
          byteLimit   = 100000,
          recordLimit = 40,
          timeLimit   = 1000
        ),
        config = PubSubSinkConfig(
          maxBytes        = 10000000,
          googleProjectId = "google-project-id",
          backoffPolicy = PubSubSinkConfig.BackoffPolicy(
            minBackoff           = 1000,
            maxBackoff           = 1000,
            totalBackoff         = 9223372036854L,
            multiplier           = 2,
            initialRpcTimeout    = 10000,
            maxRpcTimeout        = 10000,
            rpcTimeoutMultiplier = 2
          ),
          startupCheckInterval = 1.second,
          retryInterval        = 10.seconds,
          gcpUserAgent         = PubSubSinkConfig.GcpUserAgent(productName = "Snowplow OSS")
        )
      ),
      bad = Config.Sink(
        name = "bad",
        buffer = Config.Buffer(
          byteLimit   = 100000,
          recordLimit = 40,
          timeLimit   = 1000
        ),
        config = PubSubSinkConfig(
          maxBytes        = 10000000,
          googleProjectId = "google-project-id",
          backoffPolicy = PubSubSinkConfig.BackoffPolicy(
            minBackoff           = 1000,
            maxBackoff           = 1000,
            totalBackoff         = 9223372036854L,
            multiplier           = 2,
            initialRpcTimeout    = 10000,
            maxRpcTimeout        = 10000,
            rpcTimeoutMultiplier = 2
          ),
          startupCheckInterval = 1.second,
          retryInterval        = 10.seconds,
          gcpUserAgent         = PubSubSinkConfig.GcpUserAgent(productName = "Snowplow OSS")
        )
      )
    ),
    telemetry = Config.Telemetry(
      disable         = false,
      interval        = 60.minutes,
      method          = "POST",
      url             = "telemetry-g.snowplowanalytics.com",
      port            = 443,
      secure          = true,
      userProvidedId  = None,
      moduleName      = None,
      moduleVersion   = None,
      instanceId      = None,
      autoGeneratedId = None
    ),
    license = Config.License(accept = true)
  )

}
