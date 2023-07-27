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
