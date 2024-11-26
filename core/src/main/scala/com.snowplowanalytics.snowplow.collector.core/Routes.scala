/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This software is made available by Snowplow Analytics, Ltd.,
  * under the terms of the Snowplow Limited Use License Agreement, Version 1.0
  * located at https://docs.snowplow.io/limited-use-license-1.1
  * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
  * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
  */
package com.snowplowanalytics.snowplow.collector.core

import cats.implicits._
import cats.effect.{Async, Sync}
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import com.comcast.ip4s.Dns

class Routes[F[_]: Async](
  enableDefaultRedirect: Boolean,
  enableRootResponse: Boolean,
  enableCrossdomainTracking: Boolean,
  service: IService[F]
) extends Http4sDsl[F] {

  implicit val dns: Dns[F] = Dns.forSync[F]

  private val corsRoute = HttpRoutes.of[F] {
    case req @ OPTIONS -> _ =>
      service.preflightResponse(req)
  }

  private val cookieRoutes = HttpRoutes.of[F] {
    case req @ POST -> Root / vendor / version =>
      val path = service.determinePath(vendor, version)
      service.cookie(
        body          = req.bodyText.compile.string.map(Some(_)),
        path          = path,
        request       = req,
        pixelExpected = false,
        contentType   = req.contentType.map(_.value.toLowerCase)
      )

    case req @ (GET | HEAD) -> Root / vendor / version =>
      val path = service.determinePath(vendor, version)
      service.cookie(
        body          = Sync[F].pure(None),
        path          = path,
        request       = req,
        pixelExpected = true,
        contentType   = None
      )

    case req @ (GET | HEAD) -> Root / ("ice.png" | "i") =>
      service.cookie(
        body          = Sync[F].pure(None),
        path          = req.pathInfo.renderString,
        request       = req,
        pixelExpected = true,
        contentType   = None
      )
  }

  def rejectRedirect = HttpRoutes.of[F] {
    case _ -> Root / "r" / _ =>
      NotFound("redirects disabled")
  }

  private val rootRoute = HttpRoutes.of[F] {
    case GET -> Root if enableRootResponse =>
      service.rootResponse
  }

  private val crossdomainRoute = HttpRoutes.of[F] {
    case GET -> Root / "crossdomain.xml" if enableCrossdomainTracking =>
      service.crossdomainResponse
  }

  val health = HttpRoutes.of[F] {
    case GET -> Root / "health" =>
      Ok("ok")
    case GET -> Root / "sink-health" =>
      service
        .sinksHealthy
        .ifM(
          ifTrue  = Ok("ok"),
          ifFalse = ServiceUnavailable("Service Unavailable")
        )
    case GET -> Root / "robots.txt" =>
      Ok("User-agent: *\nDisallow: /\n\nUser-agent: Googlebot\nDisallow: /\n\nUser-agent: AdsBot-Google\nDisallow: /")
  }

  val value: HttpRoutes[F] = {
    val routes = corsRoute <+> cookieRoutes <+> rootRoute <+> crossdomainRoute
    if (enableDefaultRedirect) routes else rejectRedirect <+> routes
  }
}
