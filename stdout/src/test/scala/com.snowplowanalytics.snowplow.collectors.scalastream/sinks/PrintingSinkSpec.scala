/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Snowplow Community License Version 1.0,
 * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
 * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
 */
package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets

import org.specs2.mutable.Specification

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import com.snowplowanalytics.snowplow.collector.stdout.PrintingSink

class PrintingSinkSpec extends Specification {

  "Printing sink" should {
    "print provided bytes encoded as BASE64 string" in {
      val baos  = new ByteArrayOutputStream()
      val sink  = new PrintingSink[IO](Integer.MAX_VALUE, new PrintStream(baos))
      val input = "Something"

      sink.storeRawEvents(List(input.getBytes(StandardCharsets.UTF_8)), "key").unsafeRunSync()

      baos.toString(StandardCharsets.UTF_8) must beEqualTo("U29tZXRoaW5n\n") // base64 of 'Something' + newline
    }
  }
}
