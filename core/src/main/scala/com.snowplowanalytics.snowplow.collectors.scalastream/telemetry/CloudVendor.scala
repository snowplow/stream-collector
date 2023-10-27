/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
  */
package com.snowplowanalytics.snowplow.collectors.scalastream.telemetry

import io.circe.Encoder

sealed trait CloudVendor

object CloudVendor {
  case object Aws extends CloudVendor
  case object Gcp extends CloudVendor

  implicit val encoder: Encoder[CloudVendor] = Encoder.encodeString.contramap[CloudVendor](_.toString().toUpperCase())
}
