package com.snowplowanalytics.snowplow.collector.core

import scala.concurrent.duration.FiniteDuration

import io.circe.config.syntax._
import io.circe.generic.semiauto._
import io.circe.Decoder
import io.circe._

import org.http4s.SameSite

case class Config[+SinkConfig](
  interface: String,
  port: Int,
  paths: Map[String, String],
  p3p: Config.P3P,
  crossDomain: Config.CrossDomain,
  cookie: Config.Cookie,
  doNotTrackCookie: Config.DoNotTrackCookie,
  cookieBounce: Config.CookieBounce,
  redirectMacro: Config.RedirectMacro,
  rootResponse: Config.RootResponse,
  cors: Config.CORS,
  streams: Config.Streams[SinkConfig],
  monitoring: Config.Monitoring,
  ssl: Config.SSL,
  enableDefaultRedirect: Boolean,
  redirectDomains: Set[String],
  preTerminationPeriod: FiniteDuration
)

object Config {

  case class P3P(
    policyRef: String,
    CP: String
  )

  case class CrossDomain(
    enabled: Boolean,
    domains: List[String],
    secure: Boolean
  )

  case class Cookie(
    enabled: Boolean,
    name: String,
    expiration: FiniteDuration,
    domains: List[String],
    fallbackDomain: Option[String],
    secure: Boolean,
    httpOnly: Boolean,
    sameSite: Option[SameSite]
  )

  case class DoNotTrackCookie(
    enabled: Boolean,
    name: String,
    value: String
  )

  case class CookieBounce(
    enabled: Boolean,
    name: String,
    fallbackNetworkUserId: String,
    forwardedProtocolHeader: Option[String]
  )

  case class RedirectMacro(
    enabled: Boolean,
    placeholder: Option[String]
  )

  case class RootResponse(
    enabled: Boolean,
    statusCode: Int,
    headers: Map[String, String],
    body: String
  )

  case class CORS(
    accessControlMaxAge: FiniteDuration
  )

  case class Streams[+SinkConfig](
    good: String,
    bad: String,
    useIpAddressAsPartitionKey: Boolean,
    sink: SinkConfig,
    buffer: Buffer
  )

  trait Sink {
    val maxBytes: Int
  }

  case class Buffer(
    byteLimit: Long,
    recordLimit: Long,
    timeLimit: Long
  )

  case class Monitoring(
    metrics: Metrics
  )

  case class Metrics(
    statsd: Statsd
  )

  case class Statsd(
    enabled: Boolean,
    hostname: String,
    port: Int,
    period: FiniteDuration,
    prefix: String
  )

  case class SSL(
    enable: Boolean,
    redirect: Boolean,
    port: Int
  )

  implicit def decoder[SinkConfig: Decoder]: Decoder[Config[SinkConfig]] = {
    implicit val p3p         = deriveDecoder[P3P]
    implicit val crossDomain = deriveDecoder[CrossDomain]
    implicit val sameSite: Decoder[SameSite] = Decoder.instance { cur =>
      cur.as[String].map(_.toLowerCase) match {
        case Right("none")   => Right(SameSite.None)
        case Right("strict") => Right(SameSite.Strict)
        case Right("lax")    => Right(SameSite.Lax)
        case Right(other) =>
          Left(DecodingFailure(s"sameSite $other is not supported. Accepted values: None, Strict, Lax", cur.history))
        case Left(err) => Left(err)
      }
    }
    implicit val cookie           = deriveDecoder[Cookie]
    implicit val doNotTrackCookie = deriveDecoder[DoNotTrackCookie]
    implicit val cookieBounce     = deriveDecoder[CookieBounce]
    implicit val redirectMacro    = deriveDecoder[RedirectMacro]
    implicit val rootResponse     = deriveDecoder[RootResponse]
    implicit val cors             = deriveDecoder[CORS]
    implicit val buffer           = deriveDecoder[Buffer]
    implicit val streams          = deriveDecoder[Streams[SinkConfig]]
    implicit val statsd           = deriveDecoder[Statsd]
    implicit val metrics          = deriveDecoder[Metrics]
    implicit val monitoring       = deriveDecoder[Monitoring]
    implicit val ssl              = deriveDecoder[SSL]
    deriveDecoder[Config[SinkConfig]]
  }
}
