package com.snowplowanalytics.snowplow.collector.stdout

import io.circe.Decoder
import io.circe.generic.semiauto._

final case class SinkConfig(
  maxBytes: Int
)

object SinkConfig {
  implicit val configDecoder: Decoder[SinkConfig] = deriveDecoder[SinkConfig]
}
