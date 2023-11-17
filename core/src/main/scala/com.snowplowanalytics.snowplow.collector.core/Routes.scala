/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This software is made available by Snowplow Analytics, Ltd.,
  * under the terms of the Snowplow Limited Use License Agreement, Version 1.0
  * located at https://docs.snowplow.io/limited-use-license-1.0
  * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
  * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
  */
package com.snowplowanalytics.snowplow.collector.core

import cats.implicits._
import cats.effect.Sync
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import com.comcast.ip4s.Dns

class Routes[F[_]: Sync](enableDefaultRedirect: Boolean, enableRootResponse: Boolean, service: IService[F])
    extends Http4sDsl[F] {

  implicit val dns: Dns[F] = Dns.forSync[F]

  private val healthRoutes = HttpRoutes.of[F] {
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
        doNotTrack    = false,
        contentType   = req.contentType.map(_.value.toLowerCase)
      )

    case req @ (GET | HEAD) -> Root / vendor / version =>
      val path = service.determinePath(vendor, version)
      service.cookie(
        body          = Sync[F].pure(None),
        path          = path,
        request       = req,
        pixelExpected = true,
        doNotTrack    = false,
        contentType   = None
      )

    case req @ (GET | HEAD) -> Root / ("ice.png" | "i") =>
      service.cookie(
        body          = Sync[F].pure(None),
        path          = req.pathInfo.renderString,
        request       = req,
        pixelExpected = true,
        doNotTrack    = false,
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

  val value: HttpApp[F] = {
    val routes = healthRoutes <+> corsRoute <+> cookieRoutes <+> rootRoute
    val res    = if (enableDefaultRedirect) routes else rejectRedirect <+> routes
    res.orNotFound
  }
}
