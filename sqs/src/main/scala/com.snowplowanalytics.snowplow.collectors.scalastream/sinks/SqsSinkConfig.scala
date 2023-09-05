package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import io.circe.Decoder
import io.circe.generic.semiauto._

import com.snowplowanalytics.snowplow.collector.core.Config

final case class SqsSinkConfig(
  maxBytes: Int,
  region: String,
  backoffPolicy: SqsSinkConfig.BackoffPolicyConfig,
  threadPoolSize: Int
) extends Config.Sink

object SqsSinkConfig {
  final case class AWSConfig(accessKey: String, secretKey: String)

  final case class BackoffPolicyConfig(minBackoff: Long, maxBackoff: Long, maxRetries: Int)

  implicit val configDecoder: Decoder[SqsSinkConfig]              = deriveDecoder[SqsSinkConfig]
  implicit val backoffPolicyDecoder: Decoder[BackoffPolicyConfig] = deriveDecoder[BackoffPolicyConfig]
}
