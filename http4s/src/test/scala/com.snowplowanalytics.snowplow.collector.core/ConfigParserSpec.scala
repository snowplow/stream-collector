package com.snowplowanalytics.snowplow.collector.core

import java.nio.file.Paths

import org.specs2.mutable.Specification

import cats.effect.IO

import cats.effect.testing.specs2.CatsEffect

import io.circe.generic.semiauto._

class ConfigParserSpec extends Specification with CatsEffect {

  "Loading the configuration" should {
    "use reference.conf and the hocon specified in the path" in {
      case class SinkConfig(foo: String, bar: String)
      implicit val decoder = deriveDecoder[SinkConfig]

      val path = Paths.get(getClass.getResource(("/test-config.hocon")).toURI())

      val expectedStreams = Config.Streams[SinkConfig](
        "good",
        "bad",
        TestUtils.testConfig.streams.useIpAddressAsPartitionKey,
        SinkConfig("hello", "world"),
        TestUtils.testConfig.streams.buffer
      )
      val expected = TestUtils
        .testConfig
        .copy[SinkConfig](
          paths   = Map.empty[String, String],
          streams = expectedStreams,
          ssl     = TestUtils.testConfig.ssl.copy(enable = true)
        )

      ConfigParser.fromPath[IO, SinkConfig](Some(path)).value.map(_ should beRight(expected))
    }
  }
}
