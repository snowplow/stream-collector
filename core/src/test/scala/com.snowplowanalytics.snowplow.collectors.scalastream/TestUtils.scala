/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
  */
package com.snowplowanalytics.snowplow.collectors.scalastream
import scala.concurrent.duration._
import com.snowplowanalytics.snowplow.collectors.scalastream.model._

object TestUtils {
  val testConf =
    CollectorConfig(
      interface = "0.0.0.0",
      port      = 8080,
      paths = Map(
        "/com.acme/track"    -> "/com.snowplowanalytics.snowplow/tp2",
        "/com.acme/redirect" -> "/r/tp2",
        "/com.acme/iglu"     -> "/com.snowplowanalytics.iglu/v1"
      ),
      p3p = P3PConfig("/w3c/p3p.xml", "NOI DSP COR NID PSA OUR IND COM NAV STA"),
      CrossDomainConfig(enabled = true, List("*"), secure = false),
      cookie = CookieConfig(
        true,
        "sp",
        365.days,
        None,
        None,
        secure   = false,
        httpOnly = false,
        sameSite = None
      ),
      doNotTrackCookie = DoNotTrackCookieConfig(false, "abc", "123"),
      cookieBounce     = CookieBounceConfig(false, "bounce", "new-nuid", None),
      redirectMacro    = RedirectMacroConfig(false, None),
      rootResponse     = RootResponseConfig(false, 404),
      cors             = CORSConfig(-1.seconds),
      streams = StreamsConfig(
        good                       = "good",
        bad                        = "bad",
        useIpAddressAsPartitionKey = false,
        sink = Kinesis(
          maxBytes             = 1000000,
          region               = "us-east-1",
          threadPoolSize       = 12,
          aws                  = AWSConfig("cpf", "cpf"),
          backoffPolicy        = KinesisBackoffPolicyConfig(500L, 1500L, 3),
          customEndpoint       = None,
          sqsGoodBuffer        = Some("good-buffer"),
          sqsBadBuffer         = Some("bad-buffer"),
          sqsMaxBytes          = 192000,
          startupCheckInterval = 1.second
        ),
        buffer = BufferConfig(4000000L, 500L, 60000L)
      ),
      monitoring              = MonitoringConfig(MetricsConfig(StatsdConfig(false, "localhost", 8125, 10.seconds))),
      telemetry               = None,
      enableDefaultRedirect   = false,
      redirectDomains         = Set("localhost"),
      terminationDeadline     = 10.seconds,
      preTerminationPeriod    = 10.seconds,
      preTerminationUnhealthy = false,
      experimental            = ExperimentalConfig(WarmupConfig(false, 2000, 2000, 3))
    )
}
