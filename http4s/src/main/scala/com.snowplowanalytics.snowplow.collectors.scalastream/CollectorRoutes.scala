package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect.Sync
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.dsl.Http4sDsl

class CollectorRoutes[F[_]: Sync]() extends Http4sDsl[F] {

  lazy val value: HttpApp[F] = HttpRoutes
    .of[F] {
      case GET -> Root / "health" =>
        Ok("OK")
    }
    .orNotFound
}
