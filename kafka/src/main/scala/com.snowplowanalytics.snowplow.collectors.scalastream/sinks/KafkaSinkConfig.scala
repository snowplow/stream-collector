package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import io.circe.Decoder
import io.circe.config.syntax.durationDecoder
import io.circe.generic.semiauto._

import scala.concurrent.duration.FiniteDuration

final case class KafkaSinkConfig(
  maxBytes: Int,
  brokers: String,
  producerConf: Map[String, String],
  startupCheckInterval: FiniteDuration,
  retryInterval: FiniteDuration
)

object KafkaSinkConfig {
  implicit val configDecoder: Decoder[KafkaSinkConfig] = deriveDecoder[KafkaSinkConfig]
}
