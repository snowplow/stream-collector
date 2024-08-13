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

import cats.effect.{Async, Resource}
import cats.implicits._
import com.avast.datadog4s.api.Tag
import com.avast.datadog4s.extension.http4s.DatadogMetricsOps
import com.avast.datadog4s.{StatsDMetricFactory, StatsDMetricFactoryConfig}
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.headers.`Strict-Transport-Security`
import org.http4s.server.Server
import org.http4s.server.middleware.{HSTS, Logger => LoggerMiddleware, Metrics, Timeout}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.net.InetSocketAddress
import javax.net.ssl.SSLContext

object HttpServer {

  implicit private def logger[F[_]: Async]: Logger[F] = Slf4jLogger.getLogger[F]

  def build[F[_]: Async](
    routes: HttpRoutes[F],
    healthRoutes: HttpRoutes[F],
    port: Int,
    secure: Boolean,
    hsts: Config.HSTS,
    networking: Config.Networking,
    metricsConfig: Config.Metrics,
    debugHttp: Config.Debug.Http
  ): Resource[F, Server] =
    for {
      withMetricsMiddleware <- createMetricsMiddleware(routes, metricsConfig)
      server                <- buildBlazeServer[F](withMetricsMiddleware, healthRoutes, port, secure, hsts, networking, debugHttp)
    } yield server

  private def createMetricsMiddleware[F[_]: Async](
    routes: HttpRoutes[F],
    metricsConfig: Config.Metrics
  ): Resource[F, HttpRoutes[F]] =
    if (metricsConfig.statsd.enabled) {
      val metricsFactory = StatsDMetricFactory.make(createStatsdConfig(metricsConfig))
      metricsFactory.evalMap(DatadogMetricsOps.builder[F](_).useDistributionBasedTimers().build()).map { metricsOps =>
        Metrics[F](metricsOps)(routes)
      }
    } else {
      Resource.pure(routes)
    }

  private def createStatsdConfig(metricsConfig: Config.Metrics): StatsDMetricFactoryConfig = {
    val server = InetSocketAddress.createUnresolved(metricsConfig.statsd.hostname, metricsConfig.statsd.port)
    val tags   = metricsConfig.statsd.tags.toVector.map { case (name, value) => Tag.of(name, value) }
    StatsDMetricFactoryConfig(Some(metricsConfig.statsd.prefix), server, defaultTags = tags)
  }

  private[core] def hstsApp[F[_]: Async](hsts: Config.HSTS, routes: HttpRoutes[F]): HttpApp[F] =
    if (hsts.enable)
      HSTS(routes.orNotFound, `Strict-Transport-Security`.unsafeFromDuration(hsts.maxAge))
    else routes.orNotFound

  private def loggerMiddleware[F[_]: Async](routes: HttpRoutes[F], config: Config.Debug.Http): HttpRoutes[F] =
    if (config.enable) {
      LoggerMiddleware.httpRoutes[F](
        logHeaders        = config.logHeaders,
        logBody           = config.logBody,
        redactHeadersWhen = config.redactHeaders.map(CIString(_)).contains(_),
        logAction         = Some((msg: String) => Logger[F].debug(msg))
      )(routes)
    } else routes

  private def timeoutMiddleware[F[_]: Async](routes: HttpRoutes[F], networking: Config.Networking): HttpRoutes[F] =
    Timeout.httpRoutes[F](timeout = networking.responseHeaderTimeout)(routes)

  private def buildBlazeServer[F[_]: Async](
    routes: HttpRoutes[F],
    healthRoutes: HttpRoutes[F],
    port: Int,
    secure: Boolean,
    hsts: Config.HSTS,
    networking: Config.Networking,
    debugHttp: Config.Debug.Http
  ): Resource[F, Server] =
    Resource.eval(Logger[F].info("Building blaze server")) >>
      BlazeServerBuilder[F]
        .bindSocketAddress(new InetSocketAddress(port))
        .withHttpApp(
          hstsApp(
            hsts,
            loggerMiddleware(timeoutMiddleware(routes, networking) <+> healthRoutes, debugHttp)
          )
        )
        .withIdleTimeout(networking.idleTimeout)
        .withMaxConnections(networking.maxConnections)
        .withResponseHeaderTimeout(networking.responseHeaderTimeout)
        .withLengthLimits(
          maxRequestLineLen = networking.maxRequestLineLength,
          maxHeadersLen     = networking.maxHeadersLength
        )
        .cond(secure, _.withSslContext(SSLContext.getDefault))
        .resource

  implicit class ConditionalAction[A](item: A) {
    def cond(cond: Boolean, action: A => A): A =
      if (cond) action(item) else item
  }
}
