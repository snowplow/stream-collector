/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
  */
package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import java.util.regex.Pattern

import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.PubSubSinkConfig._

import org.specs2.mutable.Specification

class GcpUserAgentSpec extends Specification {

  "createUserAgent" should {
    "create user agent string correctly" in {
      val gcpUserAgent      = GcpUserAgent(productName = "Snowplow OSS")
      val resultUserAgent   = PubSubSink.createUserAgent(gcpUserAgent)
      val expectedUserAgent = s"Snowplow OSS/collector (GPN:Snowplow;)"

      val userAgentRegex = Pattern.compile(
        """(?iU)(?:[^\(\)\/]+\/[^\/]+\s+)*(?:[^\s][^\(\)\/]+\/[^\/]+\s?\([^\(\)]*)gpn:(.*)[;\)]"""
      )
      val matcher         = userAgentRegex.matcher(resultUserAgent)
      val matched         = if (matcher.find()) Some(matcher.group(1)) else None
      val expectedMatched = "Snowplow;"

      resultUserAgent must beEqualTo(expectedUserAgent)
      matched must beSome(expectedMatched)
    }
  }
}
