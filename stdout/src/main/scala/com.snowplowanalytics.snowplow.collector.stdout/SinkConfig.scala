package com.snowplowanalytics.snowplow.collector.stdout

import io.circe.Decoder
import io.circe.generic.semiauto._

import com.snowplowanalytics.snowplow.collector.core.Config

final case class SinkConfig(
  maxBytes: Int
) extends Config.Sink

object SinkConfig {
  implicit val configDecoder: Decoder[SinkConfig] = deriveDecoder[SinkConfig]
}
