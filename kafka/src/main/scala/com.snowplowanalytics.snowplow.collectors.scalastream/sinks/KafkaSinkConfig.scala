package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import io.circe.Decoder
import io.circe.generic.semiauto._

final case class KafkaSinkConfig(
  maxBytes: Int,
  brokers: String,
  retries: Int,
  producerConf: Option[Map[String, String]]
)

object KafkaSinkConfig {
  implicit val configDecoder: Decoder[KafkaSinkConfig] = deriveDecoder[KafkaSinkConfig]
}
