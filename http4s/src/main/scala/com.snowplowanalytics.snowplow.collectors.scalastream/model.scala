package com.snowplowanalytics.snowplow.collectors.scalastream

import scala.concurrent.duration._

import org.http4s.SameSite

import io.circe.Json

object model {

  /**
    * Case class for holding both good and
    * bad sinks for the Stream Collector.
    */
  final case class CollectorSinks[F[_]](good: Sink[F], bad: Sink[F])

  /**
    * Case class for holding the results of
    * splitAndSerializePayload.
    *
    * @param good All good results
    * @param bad  All bad results
    */
  final case class EventSerializeResult(good: List[Array[Byte]], bad: List[Array[Byte]])

  /**
    * Class for the result of splitting a too-large array of events in the body of a POST request
    *
    * @param goodBatches     List of batches of events
    * @param failedBigEvents List of events that were too large
    */
  final case class SplitBatchResult(goodBatches: List[List[Json]], failedBigEvents: List[Json])

  final case class CookieConfig(
    enabled: Boolean,
    name: String,
    expiration: FiniteDuration,
    domains: List[String],
    fallbackDomain: Option[String],
    secure: Boolean,
    httpOnly: Boolean,
    sameSite: Option[SameSite]
  )

  final case class CORSConfig(accessControlMaxAge: FiniteDuration)

  final case class CollectorConfig(
    paths: Map[String, String],
    cookie: CookieConfig,
    cors: CORSConfig
  ) {
    val cookieConfig = if (cookie.enabled) Some(cookie) else None
  }
}
