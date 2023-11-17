/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
  */
package com.snowplowanalytics.snowplow.collector.core

import scala.concurrent.duration._

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
  telemetry: Config.Telemetry,
  ssl: Config.SSL,
  networking: Config.Networking,
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
    good: Sink[SinkConfig],
    bad: Sink[SinkConfig],
    useIpAddressAsPartitionKey: Boolean
  )

  final case class Sink[+SinkConfig](name: String, buffer: Buffer, config: SinkConfig)

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

  final case class Telemetry(
    // General params
    disable: Boolean,
    interval: FiniteDuration,
    // http params
    method: String,
    url: String,
    port: Int,
    secure: Boolean,
    // Params injected by deployment scripts
    userProvidedId: Option[String],
    moduleName: Option[String],
    moduleVersion: Option[String],
    instanceId: Option[String],
    autoGeneratedId: Option[String]
  )

  case class Networking(
    maxConnections: Int,
    idleTimeout: FiniteDuration
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
    implicit val statsd           = deriveDecoder[Statsd]
    implicit val metrics          = deriveDecoder[Metrics]
    implicit val monitoring       = deriveDecoder[Monitoring]
    implicit val ssl              = deriveDecoder[SSL]
    implicit val telemetry        = deriveDecoder[Telemetry]
    implicit val networking       = deriveDecoder[Networking]
    implicit val sinkConfig       = newDecoder[SinkConfig].or(legacyDecoder[SinkConfig])
    implicit val streams          = deriveDecoder[Streams[SinkConfig]]

    deriveDecoder[Config[SinkConfig]]
  }

  implicit private val buffer: Decoder[Buffer] = deriveDecoder[Buffer]

  /**
    * streams {
    *   good {
    *     name: "good-name"
    *     buffer {...}
    *     // rest of the sink config...
    *   }
    *   bad {
    *     name: "bad-name"
    *     buffer {...}
    *     // rest of the sink config...
    *   }
    * }
    */
  private def newDecoder[SinkConfig: Decoder]: Decoder[Sink[SinkConfig]] =
    Decoder.instance { cursor => // cursor is at 'good'/'bad' section level
      for {
        sinkName <- cursor.get[String]("name")
        config   <- cursor.as[SinkConfig]
        buffer   <- cursor.get[Buffer]("buffer")
      } yield Sink(sinkName, buffer, config)
    }

  /**
    * streams {
    *   good = "good-name"
    *   bad = "bad-name"
    *   buffer {...} //shared by good and bad
    *   sink {...} //shared by good and bad
    * }
    */
  private def legacyDecoder[SinkConfig: Decoder]: Decoder[Sink[SinkConfig]] =
    Decoder.instance { cursor => //cursor is at 'good'/'bad' section level
      for {
        sinkName <- cursor.as[String]
        config   <- cursor.up.get[SinkConfig]("sink") //up first to the 'streams' section
        buffer   <- cursor.up.get[Buffer]("buffer") //up first to the 'streams' section
      } yield Sink(sinkName, buffer, config)
    }
}