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
package com.snowplowanalytics.snowplow.collectors.scalastream.telemetry

import io.circe.Encoder

sealed trait CloudVendor

object CloudVendor {
  case object Aws extends CloudVendor
  case object Gcp extends CloudVendor

  implicit val encoder: Encoder[CloudVendor] = Encoder.encodeString.contramap[CloudVendor](_.toString().toUpperCase())
}
