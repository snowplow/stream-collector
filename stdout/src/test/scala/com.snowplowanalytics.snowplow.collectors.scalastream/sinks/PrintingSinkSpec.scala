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
