/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
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
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.KafkaSinkConfig
import org.http4s.SameSite
import org.specs2.mutable.Specification

import java.nio.file.Paths
import scala.concurrent.duration.DurationInt

class KafkaConfigSpec extends Specification with CatsEffect {

  "Config parser" should {
    "be able to parse extended kafka config" in {
      assert(
        resource = "/config.kafka.extended.hocon",
        expectedResult = Right(
          KafkaConfigSpec
            .expectedConfig
            .copy(
              monitoring = Config.Monitoring(
                Config.Metrics(
                  KafkaConfigSpec.expectedConfig.monitoring.metrics.statsd.copy(tags = Map("app" -> "collector"))
                )
              )
            )
        )
      )
    }
    "be able to parse minimal kafka config" in {
      assert(
        resource       = "/config.kafka.minimal.hocon",
        expectedResult = Right(KafkaConfigSpec.expectedConfig)
      )
    }
  }

  private def assert(resource: String, expectedResult: Either[ExitCode, Config[KafkaSinkConfig]]) = {
    val path = Paths.get(getClass.getResource(resource).toURI)
    ConfigParser.fromPath[IO, KafkaSinkConfig](Some(path)).value.map { result =>
      result must beEqualTo(expectedResult)
    }
  }
}

object KafkaConfigSpec {

  private val expectedConfig = Config[KafkaSinkConfig](
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
    streams = Config.Streams(
      good = Config.Sink(
        name = "good",
        buffer = Config.Buffer(
          byteLimit   = 3145728,
          recordLimit = 500,
          timeLimit   = 5000
        ),
        config = KafkaSinkConfig(
          maxBytes = 1000000,
          brokers  = "localhost:9092,another.host:9092",
          retries  = 10,
          producerConf = Some(
            Map(
              "security.protocol" -> "SASL_SSL",
              "sasl.mechanism"    -> "OAUTHBEARER",
              "sasl.jaas.config"  -> "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;"
            )
          )
        )
      ),
      bad = Config.Sink(
        name = "bad",
        buffer = Config.Buffer(
          byteLimit   = 3145728,
          recordLimit = 500,
          timeLimit   = 5000
        ),
        config = KafkaSinkConfig(
          maxBytes = 1000000,
          brokers  = "localhost:9092,another.host:9092",
          retries  = 10,
          producerConf = Some(
            Map(
              "security.protocol" -> "SASL_SSL",
              "sasl.mechanism"    -> "OAUTHBEARER",
              "sasl.jaas.config"  -> "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;"
            )
          )
        )
      ),
      useIpAddressAsPartitionKey = false
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
    networking = Config.Networking(
      maxConnections        = 1024,
      idleTimeout           = 610.seconds,
      responseHeaderTimeout = 5.seconds,
      maxRequestLineLength  = 20480,
      maxHeadersLength      = 40960
    ),
    license = Config.License(accept = true),
    debug = Config
      .Debug
      .Debug(Config.Debug.Http(enable = false, logHeaders = true, logBody = false, redactHeaders = List.empty))
  )
}
