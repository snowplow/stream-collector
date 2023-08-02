package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect.Sync
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.dsl.Http4sDsl

class CollectorRoutes[F[_]: Sync](good: Sink[F], bad: Sink[F]) extends Http4sDsl[F] {

  val _ = (good, bad)

  lazy val value: HttpApp[F] = HttpRoutes
    .of[F] {
      case GET -> Root / "health" =>
        Ok("OK")
    }
    .orNotFound
}
