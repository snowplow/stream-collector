package com.snowplowanalytics.snowplow.collector.core

import java.nio.file.Paths
import org.specs2.mutable.Specification
import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import com.snowplowanalytics.snowplow.collector.core.Config.Buffer
import io.circe.generic.semiauto._

class ConfigParserSpec extends Specification with CatsEffect {

  "Loading the configuration" should {
    "use reference.conf and the hocon specified in the path" >> {
      "for new-style config" in {
        assert(resource = "/test-config-new-style.hocon")
      }
      "for old-style config" in {
        assert(resource = "/test-config-old-style.hocon")
      }
    }
  }

  private def assert(resource: String) = {
    case class SinkConfig(foo: String, bar: String)
    implicit val decoder = deriveDecoder[SinkConfig]

    val path = Paths.get(getClass.getResource(resource).toURI)

    val expectedStreams = Config.Streams[SinkConfig](
      good = Config.Sink(
        name = "good",
        buffer = Buffer(
          3145728,
          500,
          5000
        ),
        SinkConfig("hello", "world")
      ),
      bad = Config.Sink(
        name = "bad",
        buffer = Buffer(
          3145728,
          500,
          5000
        ),
        SinkConfig("hello", "world")
      ),
      TestUtils.testConfig.streams.useIpAddressAsPartitionKey
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
