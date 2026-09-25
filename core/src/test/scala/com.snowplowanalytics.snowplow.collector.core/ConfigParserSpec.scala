package com.snowplowanalytics.snowplow.collector.core

import java.nio.file.Paths
import org.specs2.mutable.Specification
import cats.Id
import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import com.snowplowanalytics.snowplow.collector.core.Config.Buffer
import com.snowplowanalytics.snowplow.streams.http.{HttpSinkConfig, HttpSinkConfigM}
import io.circe.generic.semiauto._
import org.http4s.Uri

import scala.concurrent.duration.DurationInt

class ConfigParserSpec extends Specification with CatsEffect {

  "Loading the configuration" should {
    "use reference.conf and the hocon specified in the path" >> {
      "for new-style config" in {
        assert(resource = "/test-config-new-style.hocon")
      }
      "for old-style config" in {
        assert(resource = "/test-config-old-style.hocon")
      }
      "for config with http sink" in {
        assert(
          resource       = "/test-http-sink-config.hocon",
          httpSinkConfig = Some(HttpSinkConfigM[Id](baseUri = Uri.unsafeFromString("http://127.0.0.1")))
        )
      }
    }
  }

  private def assert(resource: String, httpSinkConfig: Option[HttpSinkConfig] = None) = {
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
      http = httpSinkConfig
    )
    val expected = TestUtils
      .testConfig
      .copy[SinkConfig](
        paths   = Map.empty[String, String],
        streams = expectedStreams,
        ssl     = TestUtils.testConfig.ssl.copy(enable = true),
        hsts    = TestUtils.testConfig.hsts.copy(enable = true, 180.days),
        license = Config.License(false)
      )

    ConfigParser.fromPath[IO, SinkConfig](Some(path)).value.map(_ should beRight(expected))
  }
}
