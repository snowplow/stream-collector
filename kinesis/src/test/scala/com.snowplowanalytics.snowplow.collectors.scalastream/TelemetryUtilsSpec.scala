package com.snowplowanalytics.snowplow.collectors.scalastream

import org.specs2.mutable.Specification

class TelemetryUtilsSpec extends Specification {

  "extractAccountId" should {
    "be able to extract account id from kinesis stream arn successfully" in {
      val streamArn = "arn:aws:kinesis:region:123456789:stream/name"
      TelemetryUtils.extractAccountId(streamArn) must beEqualTo("123456789")
    }
  }
}
