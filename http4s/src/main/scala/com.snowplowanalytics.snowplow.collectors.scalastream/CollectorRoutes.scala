package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.implicits._
import cats.effect.Sync
import org.typelevel.ci.CIString
import org.http4s.{HttpApp, HttpRoutes, Request}
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
    case req @ (POST | GET | HEAD) -> Root / vendor / version =>
      val path        = collectorService.determinePath(vendor, version)
      val userAgent   = extractHeader(req, "User-Agent")
      val referer     = extractHeader(req, "Referer")
      val spAnonymous = extractHeader(req, "SP-Anonymous")
      val hostname    = req.remoteHost.map(_.map(_.toString))
      val ip          = req.remoteAddr.map(_.toUriString)

      req.method match {
        case POST =>
          collectorService.cookie(
            queryString   = Some(req.queryString),
            body          = req.bodyText.compile.string.map(Some(_)),
            path          = path,
            cookie        = None, //TODO: cookie will be added later
            userAgent     = userAgent,
            refererUri    = referer,
            hostname      = hostname,
            ip            = ip, // TODO: Do not set the ip if request contains SP-Anonymous header
            request       = req,
            pixelExpected = false,
            doNotTrack    = false,
            contentType   = req.contentType.map(_.value.toLowerCase),
            spAnonymous   = spAnonymous
          )
        case GET | HEAD =>
          collectorService.cookie(
            queryString   = Some(req.queryString),
            body          = Sync[F].delay(None),
            path          = path,
            cookie        = None, //TODO: cookie will be added later
            userAgent     = userAgent,
            refererUri    = referer,
            hostname      = hostname,
            ip            = ip, // TODO: Do not set the ip if request contains SP-Anonymous header
            request       = req,
            pixelExpected = true,
            doNotTrack    = false,
            contentType   = None,
            spAnonymous   = spAnonymous
          )
      }
  }

  val value: HttpApp[F] = (healthRoutes <+> cookieRoutes).orNotFound

  def extractHeader(req: Request[F], headerName: String): Option[String] =
    req.headers.get(CIString(headerName)).map(_.head.value)
}
