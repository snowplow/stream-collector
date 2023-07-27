/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
  */
package com.snowplowanalytics.snowplow.collectors.scalastream.config

import pureconfig.ConfigSource
import pureconfig.error.ConfigReaderFailure
import org.specs2.mutable.Specification

import com.snowplowanalytics.snowplow.collectors.scalastream.model.CollectorConfig

class ConfigReaderSpec extends Specification {

  "The collector config reader" should {
    "parse a valid config file" in {
      val source = getConfig("/configs/valid-config.hocon")
      source.load[CollectorConfig] must beRight
    }

    "reject a config file with invalid fallbackDomain" in {
      val source = getConfig("/configs/invalid-fallback-domain.hocon")
      source.load[CollectorConfig] must beLeft.like {
        case failures =>
          failures.toList must contain { (failure: ConfigReaderFailure) =>
            failure.description must startWith("fallbackDomain contains invalid character")
          }
      }
    }
  }

  def getConfig(resourceName: String): ConfigSource =
    ConfigSource.url(getClass.getResource(resourceName)).withFallback(ConfigSource.default)

}
