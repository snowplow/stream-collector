/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
  */
package com.snowplowanalytics.snowplow.collector.core

import cats.effect.{Async, Resource}
import cats.implicits._
import org.http4s.HttpApp
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.server.Server
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.net.InetSocketAddress
import javax.net.ssl.SSLContext

object HttpServer {

  implicit private def logger[F[_]: Async]: Logger[F] = Slf4jLogger.getLogger[F]

  def build[F[_]: Async](
    app: HttpApp[F],
    port: Int,
    secure: Boolean,
    networking: Config.Networking
  ): Resource[F, Server] =
    buildBlazeServer[F](app, port, secure, networking)

  private def buildBlazeServer[F[_]: Async](
    app: HttpApp[F],
    port: Int,
    secure: Boolean,
    networking: Config.Networking
  ): Resource[F, Server] =
    Resource.eval(Logger[F].info("Building blaze server")) >>
      BlazeServerBuilder[F]
        .bindSocketAddress(new InetSocketAddress(port))
        .withHttpApp(app)
        .withIdleTimeout(networking.idleTimeout)
        .withMaxConnections(networking.maxConnections)
        .cond(secure, _.withSslContext(SSLContext.getDefault))
        .resource

  implicit class ConditionalAction[A](item: A) {
    def cond(cond: Boolean, action: A => A): A =
      if (cond) action(item) else item
  }
}
