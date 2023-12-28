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
import org.http4s.HttpRoutes
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.server.Server
import org.http4s.server.middleware.Metrics
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.net.InetSocketAddress
import javax.net.ssl.SSLContext

object HttpServer {

  implicit private def logger[F[_]: Async]: Logger[F] = Slf4jLogger.getLogger[F]

  def build[F[_]: Async](
    routes: HttpRoutes[F],
    port: Int,
    secure: Boolean,
    networking: Config.Networking,
    metricsConfig: Config.Metrics
  ): Resource[F, Server] =
    for {
      withMetricsMiddleware <- createMetricsMiddleware(routes, metricsConfig)
      server                <- buildBlazeServer[F](withMetricsMiddleware, port, secure, networking)
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
    val tags   = metricsConfig.statsd.tags.toSeq.map { case (name, value) => Tag.of(name, value) }
    StatsDMetricFactoryConfig(Some(metricsConfig.statsd.prefix), server, defaultTags = tags)
  }

  private def buildBlazeServer[F[_]: Async](
    routes: HttpRoutes[F],
    port: Int,
    secure: Boolean,
    networking: Config.Networking
  ): Resource[F, Server] =
    Resource.eval(Logger[F].info("Building blaze server")) >>
      BlazeServerBuilder[F]
        .bindSocketAddress(new InetSocketAddress(port))
        .withHttpApp(routes.orNotFound)
        .withIdleTimeout(networking.idleTimeout)
        .withMaxConnections(networking.maxConnections)
        .cond(secure, _.withSslContext(SSLContext.getDefault))
        .resource

  implicit class ConditionalAction[A](item: A) {
    def cond(cond: Boolean, action: A => A): A =
      if (cond) action(item) else item
  }
}
