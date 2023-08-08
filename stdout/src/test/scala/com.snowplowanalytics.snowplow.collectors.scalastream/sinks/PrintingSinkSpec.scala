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
package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.snowplowanalytics.snowplow.collectors.scalastream.PrintingSink
import org.specs2.mutable.Specification

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets

class PrintingSinkSpec extends Specification {

  "Printing sink" should {
    "print provided bytes encoded as BASE64 string" in {
      val baos  = new ByteArrayOutputStream()
      val sink  = new PrintingSink[IO](new PrintStream(baos))
      val input = "Something"

      sink.storeRawEvents(List(input.getBytes(StandardCharsets.UTF_8)), "key").unsafeRunSync()

      baos.toString(StandardCharsets.UTF_8) must beEqualTo("U29tZXRoaW5n\n") // base64 of 'Something' + newline
    }
  }
}
