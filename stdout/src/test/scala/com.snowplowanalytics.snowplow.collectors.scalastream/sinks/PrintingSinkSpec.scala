/*
 * Copyright (c) 2013-2022 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, and
 * you may not use this file except in compliance with the Apache License
 * Version 2.0.  You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Apache License Version 2.0 is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
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
