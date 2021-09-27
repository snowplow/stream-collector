package com.snowplowanalytics.snowplow.collectors.scalastream.telemetry

import io.circe.Encoder

sealed trait CloudVendor

object CloudVendor {
  case object Aws extends CloudVendor
  case object Gcp extends CloudVendor

  implicit val encoder: Encoder[CloudVendor] = Encoder.encodeString.contramap[CloudVendor](_.toString().toUpperCase())
}
