package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import io.circe.Decoder
import io.circe.config.syntax.durationDecoder
import io.circe.generic.semiauto._

import scala.concurrent.duration.FiniteDuration

final case class PubSubSinkConfig(
  maxBytes: Int,
  googleProjectId: String,
  startupCheckInterval: FiniteDuration,
  retryInterval: FiniteDuration,
  emulatorHost: Option[String]
)

object PubSubSinkConfig {

  implicit val configDecoder: Decoder[PubSubSinkConfig] = deriveDecoder[PubSubSinkConfig]
}
