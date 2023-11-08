package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.PubSubSinkConfig._
import io.circe.Decoder
import io.circe.config.syntax.durationDecoder
import io.circe.generic.semiauto._

import scala.concurrent.duration.FiniteDuration

final case class PubSubSinkConfig(
  maxBytes: Int,
  googleProjectId: String,
  backoffPolicy: BackoffPolicy,
  startupCheckInterval: FiniteDuration,
  retryInterval: FiniteDuration,
  gcpUserAgent: GcpUserAgent
)

object PubSubSinkConfig {

  final case class BackoffPolicy(
    minBackoff: Long,
    maxBackoff: Long,
    totalBackoff: Long,
    multiplier: Double,
    initialRpcTimeout: Long,
    maxRpcTimeout: Long,
    rpcTimeoutMultiplier: Double
  )

  final case class GcpUserAgent(productName: String)

  implicit val configDecoder: Decoder[PubSubSinkConfig] = deriveDecoder[PubSubSinkConfig]
  implicit val backoffPolicyConfigDecoder: Decoder[BackoffPolicy] =
    deriveDecoder[BackoffPolicy]
  implicit val gcpUserAgentDecoder: Decoder[GcpUserAgent] = deriveDecoder[GcpUserAgent]
}
