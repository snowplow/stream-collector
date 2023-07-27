/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Snowplow Community License Version 1.0,
 * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
 * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
 */
package com.snowplowanalytics.snowplow.collectors.scalastream
package sinks

import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.KinesisSink._

import org.specs2.mutable.Specification

class KinesisSinkSpec extends Specification {
  val event = Events("a".getBytes, "b")

  "KinesisSink.split" should {
    "return empty list if given an empty batch" in {
      val emptyBatch = List.empty[Events]

      split(emptyBatch, getByteSize, 1, 10) mustEqual List.empty
      split(emptyBatch, getByteSize, 10, 1) mustEqual List.empty
      // Edge case that we shouldn't hit. The test simply confirms the behaviour.
      split(emptyBatch, getByteSize, 0, 0) mustEqual List.empty
    }

    "correctly split batches, according to maxRecords setting" in {
      val batch1 = List.fill(10)(event)
      val batch2 = List.fill(1)(event)

      val res1 = split(batch1, getByteSize, 3, 1000)
      val res2 = split(batch2, getByteSize, 3, 1000)
      // Edge case that we shouldn't hit. The test simply confirms the behaviour.
      val res3 = split(batch1, getByteSize, 0, 1000)

      res1.length mustEqual 4
      res2.length mustEqual 1
      (res3.length mustEqual 10).and(res3.forall(_ must not be empty))
    }

    "correctly split batches, according to maxBytes setting" in {
      val batch1 = List.fill(10)(event)
      val batch2 = List.fill(1)(event)

      val res1 = split(batch1, getByteSize, 1000, 3)
      val res2 = split(batch2, getByteSize, 1000, 3)
      // Edge case that we shouldn't hit. The test simply confirms the behaviour.
      val res3 = split(batch1, getByteSize, 1000, 0)

      res1.length mustEqual 4
      res2.length mustEqual 1
      (res3.length mustEqual 10).and(res3.forall(_ must not be empty))
    }
  }
}
