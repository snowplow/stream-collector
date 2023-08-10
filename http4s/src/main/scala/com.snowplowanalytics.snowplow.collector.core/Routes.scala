package com.snowplowanalytics.snowplow.collector.core

import cats.implicits._
import cats.effect.Sync
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import com.comcast.ip4s.Dns

class Routes[F[_]: Sync](enableDefaultRedirect: Boolean, service: IService[F]) extends Http4sDsl[F] {

  implicit val dns: Dns[F] = Dns.forSync[F]

  private val healthRoutes = HttpRoutes.of[F] {
    case GET -> Root / "health" =>
      Ok("OK")
    case GET -> Root / "sink-health" =>
      service
        .sinksHealthy
        .ifM(
          ifTrue  = Ok("OK"),
          ifFalse = ServiceUnavailable("Service Unavailable")
        )

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

  val value: HttpApp[F] = {
    val routes = healthRoutes <+> corsRoute <+> cookieRoutes
    val res    = if (enableDefaultRedirect) routes else rejectRedirect <+> routes
    res.orNotFound
  }
}
