package com.snowplowanalytics.snowplow.collectors.scalastream

import org.specs2.mutable.Specification

class TelemetryUtilsSpec extends Specification {

  "extractAccountId" should {
    "be able to extract account id from sqs queue url successfully" in {
      val queueUrl = "https://sqs.region.amazonaws.com/123456789/queue"
      TelemetryUtils.extractAccountId(queueUrl) must beEqualTo("123456789")
    }
  }
}
