package com.snowplowanalytics.snowplow.collectors.scalastream.telemetry

sealed trait CloudVendor {
  override def toString: String = this match {
    case CloudVendor.Aws => "AWS"
    case CloudVendor.Gcp => "GCP"
  }
}

object CloudVendor {
  case object Aws extends CloudVendor
  case object Gcp extends CloudVendor
}
