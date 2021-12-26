/**
  * Copyright (c) 2014-2021 Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Apache License Version 2.0,
  * and you may not use this file except in compliance with the Apache
  * License Version 2.0.
  * You may obtain a copy of the Apache License Version 2.0 at
  * http://www.apache.org/licenses/LICENSE-2.0.
  *
  * Unless required by applicable law or agreed to in writing,
  * software distributed under the Apache License Version 2.0 is distributed
  * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
  * either express or implied.
  *
  * See the Apache License Version 2.0 for the specific language
  * governing permissions and limitations there under.
  */
package com.snowplowanalytics.snowplow.collectors.scalastream.config

import com.snowplowanalytics.snowplow.collectors.scalastream.Collector
import com.snowplowanalytics.snowplow.collectors.scalastream.model._
import org.specs2.mutable.Specification
import org.specs2.specification.core.{Fragment, Fragments}

import java.nio.file.Paths
import scala.concurrent.duration.DurationInt

abstract class ConfigSpec extends Specification {

  def configRefFactory(app: String): CollectorConfig = CollectorConfig(
    interface = "0.0.0.0",
    port      = 80,
    paths     = Map.empty[String, String],
    p3p = P3PConfig(
      policyRef = "/w3c/p3p.xml",
      CP        = "NOI DSP COR NID PSA OUR IND COM NAV STA"
    ),
    crossDomain = CrossDomainConfig(
      enabled = false,
      domains = List("*"),
      secure  = true
    ),
    cookie = CookieConfig(
      enabled        = true,
      expiration     = 365.days,
      name           = "sp",
      domains        = None,
      fallbackDomain = None,
      secure         = true,
      httpOnly       = true,
      sameSite       = Some("None")
    ),
    doNotTrackCookie = DoNotTrackCookieConfig(
      enabled = false,
      name    = "",
      value   = ""
    ),
    cookieBounce = CookieBounceConfig(
      enabled                 = false,
      name                    = "n3pc",
      fallbackNetworkUserId   = "00000000-0000-4000-A000-000000000000",
      forwardedProtocolHeader = None
    ),
    redirectMacro = RedirectMacroConfig(
      enabled     = false,
      placeholder = None
    ),
    rootResponse = RootResponseConfig(
      enabled    = false,
      statusCode = 302,
      headers    = Map.empty[String, String],
      body       = ""
    ),
    cors = CORSConfig(60.minutes),
    prometheusMetrics = PrometheusMetricsConfig(
      enabled                  = false,
      durationBucketsInSeconds = None
    ),
    telemetry             = Some(TelemetryConfig()),
    ssl                   = SSLConfig(enable = false, redirect = false, port = 443),
    enableDefaultRedirect = false,
    enableStartupChecks   = true,
    streams = StreamsConfig(
      good                       = "good",
      bad                        = "bad",
      useIpAddressAsPartitionKey = false,
      buffer =
        if (app == "pubsub")
          BufferConfig(
            byteLimit   = 100000,
            recordLimit = 40,
            timeLimit   = 1000
          )
        else
          BufferConfig(
            byteLimit   = 3145728,
            recordLimit = 500,
            timeLimit   = 5000
          ),
      sink = sinkConfigRefFactory(app)
    )
  )

  def sinkConfigRefFactory(app: String): SinkConfig = app match {
    case "nsq"   => Nsq("nsqHost", 4150)
    case "kafka" => Kafka("localhost:9092,another.host:9092", 10, None)
    case "pulsar" => Pulsar("pulsar://localhost:6650,another.host:6650")
    case "pubsub" =>
      GooglePubSub(
        googleProjectId = "googleProjectId",
        backoffPolicy = GooglePubSubBackoffPolicyConfig(
          minBackoff   = 1000,
          maxBackoff   = 1000,
          totalBackoff = 10000,
          multiplier   = 2
        )
      )
    case "sqs" =>
      Sqs(
        region         = "eu-central-1",
        threadPoolSize = 10,
        aws = AWSConfig(
          accessKey = "iam",
          secretKey = "iam"
        ),
        backoffPolicy = SqsBackoffPolicyConfig(
          minBackoff = 3000,
          maxBackoff = 600000
        )
      )
    case "stdout" => Stdout
    case "kinesis" =>
      Kinesis(
        region         = "eu-central-1",
        threadPoolSize = 10,
        aws = AWSConfig(
          accessKey = "iam",
          secretKey = "iam"
        ),
        backoffPolicy = KinesisBackoffPolicyConfig(
          minBackoff = 3000,
          maxBackoff = 600000
        ),
        sqsBadBuffer   = None,
        sqsGoodBuffer  = None,
        customEndpoint = None
      )
  }

  def makeConfigTest(app: String, appVer: String, scalaVer: String): Fragments = {
    object stubCollector extends Collector {
      def appName      = app
      def appVersion   = appVer
      def scalaVersion = scalaVer
    }

    "Config.parseConfig" >> Fragment.foreach(
      Seq(("minimal", app), ("extended", app))
    ) {
      case (suffix, app) =>
        s"accept example $suffix $app config" >> {
          val config      = Paths.get(getClass.getResource(s"/config.$app.$suffix.hocon").toURI)
          val argv        = Array("--config", config.toString)
          val (result, _) = stubCollector.parseConfig(argv)
          (result must be).equalTo(configRefFactory(app))
        }
    }
  }
}
