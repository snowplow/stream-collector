/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This software is made available by Snowplow Analytics, Ltd.,
  * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
  * located at https://docs.snowplow.io/limited-use-license-1.1
  * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
  * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
  */
package com.snowplowanalytics.snowplow.collectors.scalastream
package sinks

import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.SqsOps._

import org.specs2.mutable.Specification

class SqsOpsSpec extends Specification {
  val event = "a".getBytes

  "SqsOps.split" should {
    "return empty list if given an empty batch" in {
      val emptyBatch = List.empty[Array[Byte]]

      split(emptyBatch, 1, 10) mustEqual List.empty
      split(emptyBatch, 10, 1) mustEqual List.empty
      // Edge case that we shouldn't hit. The test simply confirms the behaviour.
      split(emptyBatch, 0, 0) mustEqual List.empty
    }

    "correctly split batches, according to maxRecords setting" in {
      val batch1 = List.fill(10)(event)
      val batch2 = List.fill(1)(event)

      val res1 = split(batch1, 3, 1000)
      val res2 = split(batch2, 3, 1000)
      // Edge case that we shouldn't hit. The test simply confirms the behaviour.
      val res3 = split(batch1, 0, 1000)

      res1.length mustEqual 4
      res2.length mustEqual 1
      (res3.length mustEqual 10).and(res3.forall(_ must not be empty))
    }

    "correctly split batches, according to maxBytes setting" in {
      val batch1 = List.fill(10)(event)
      val batch2 = List.fill(1)(event)

      val res1 = split(batch1, 1000, 3)
      val res2 = split(batch2, 1000, 3)
      // Edge case that we shouldn't hit. The test simply confirms the behaviour.
      val res3 = split(batch1, 1000, 0)

      res1.length mustEqual 4
      res2.length mustEqual 1
      (res3.length mustEqual 10).and(res3.forall(_ must not be empty))
    }
  }
}
