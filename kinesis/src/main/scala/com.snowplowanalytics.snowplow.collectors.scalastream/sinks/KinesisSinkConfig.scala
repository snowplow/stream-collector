package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import com.snowplowanalytics.snowplow.collector.core.Config
import io.circe.Decoder
import io.circe.generic.semiauto._
import io.circe.config.syntax.durationDecoder

import scala.concurrent.duration.FiniteDuration

final case class KinesisSinkConfig(
  maxBytes: Int,
  region: String,
  threadPoolSize: Int,
  backoffPolicy: KinesisSinkConfig.BackoffPolicy,
  customEndpoint: Option[String],
  sqsGoodBuffer: Option[String],
  sqsBadBuffer: Option[String],
  sqsMaxBytes: Int,
  startupCheckInterval: FiniteDuration
) extends Config.Sink {
  val endpoint = customEndpoint.getOrElse(region match {
    case cn @ "cn-north-1"     => s"https://kinesis.$cn.amazonaws.com.cn"
    case cn @ "cn-northwest-1" => s"https://kinesis.$cn.amazonaws.com.cn"
    case _                     => s"https://kinesis.$region.amazonaws.com"
  })
}

object KinesisSinkConfig {
  final case class AWSConfig(accessKey: String, secretKey: String)

  final case class BackoffPolicy(minBackoff: Long, maxBackoff: Long, maxRetries: Int)
  implicit val configDecoder: Decoder[KinesisSinkConfig] = deriveDecoder[KinesisSinkConfig]
  implicit val awsConfigDecoder: Decoder[AWSConfig]      = deriveDecoder[AWSConfig]
  implicit val backoffPolicyConfigDecoder: Decoder[BackoffPolicy] =
    deriveDecoder[BackoffPolicy]
}
