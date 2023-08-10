package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.implicits._
import cats.effect.Sync
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import com.comcast.ip4s.Dns

class CollectorRoutes[F[_]: Sync](collectorService: Service[F]) extends Http4sDsl[F] {

  implicit val dns: Dns[F] = Dns.forSync[F]

  private val healthRoutes = HttpRoutes.of[F] {
    case GET -> Root / "health" =>
      Ok("OK")
  }

  private val cookieRoutes = HttpRoutes.of[F] {
    case req @ POST -> Root / vendor / version =>
      val path = collectorService.determinePath(vendor, version)
      collectorService.cookie(
        body          = req.bodyText.compile.string.map(Some(_)),
        path          = path,
        cookie        = None, //TODO: cookie will be added later
        request       = req,
        pixelExpected = false,
        doNotTrack    = false,
        contentType   = req.contentType.map(_.value.toLowerCase)
      )

    case req @ (GET | HEAD) -> Root / vendor / version =>
      val path = collectorService.determinePath(vendor, version)
      collectorService.cookie(
        body          = Sync[F].pure(None),
        path          = path,
        cookie        = None, //TODO: cookie will be added later
        request       = req,
        pixelExpected = true,
        doNotTrack    = false,
        contentType   = None
      )

    case req @ (GET | HEAD) -> Root / ("ice.png" | "i") =>
      collectorService.cookie(
        body          = Sync[F].pure(None),
        path          = req.pathInfo.renderString,
        cookie        = None, //TODO: cookie will be added later
        request       = req,
        pixelExpected = true,
        doNotTrack    = false,
        contentType   = None
      )
  }

  val value: HttpApp[F] = (healthRoutes <+> cookieRoutes).orNotFound
}
