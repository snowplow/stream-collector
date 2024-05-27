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
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.netty.server.NettyServerBuilder
import org.http4s.armeria.server.ArmeriaServerBuilder
import com.comcast.ip4s._
import fs2.io.net.Network
import fs2.io.net.tls.TLSContext
import org.http4s.headers.`Strict-Transport-Security`
import org.http4s.server.Server
import org.http4s.server.middleware.{HSTS, Logger => LoggerMiddleware, Metrics, Timeout}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.net.InetSocketAddress
import javax.net.ssl.SSLContext
import io.netty.handler.ssl.{ClientAuth, JdkSslContext}
import io.netty.handler.ssl.IdentityCipherSuiteFilter
import io.netty.handler.ssl.ApplicationProtocolConfig
import java.util.Properties
import javax.net.ssl.KeyManagerFactory
import java.security.KeyStore
import java.io.FileInputStream

object HttpServer {

  implicit private def logger[F[_]: Async]: Logger[F] = Slf4jLogger.getLogger[F]

  def build[F[_]: Async: Network](
    routes: HttpRoutes[F],
    port: Int,
    backend: Config.Experimental.Backend,
    secure: Boolean,
    hsts: Config.HSTS,
    networking: Config.Networking,
    metricsConfig: Config.Metrics,
    debugHttp: Config.Debug.Http
  ): Resource[F, Server] =
    for {
      withMetricsMiddleware <- createMetricsMiddleware(routes, metricsConfig)
      server                <- buildServer(backend)(withMetricsMiddleware, port, secure, hsts, networking, debugHttp)
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

  private def buildServer[F[_]: Async: Network](backend: Config.Experimental.Backend)(
    routes: HttpRoutes[F],
    port: Int,
    secure: Boolean,
    hsts: Config.HSTS,
    networking: Config.Networking,
    debugHttp: Config.Debug.Http
  ) = backend match {
    case Config.Experimental.Backend.Ember   => buildEmberServer(routes, port, secure, hsts, networking, debugHttp)
    case Config.Experimental.Backend.Blaze   => buildBlazeServer(routes, port, secure, hsts, networking, debugHttp)
    case Config.Experimental.Backend.Netty   => buildNettyServer(routes, port, secure, hsts, networking, debugHttp)
    case Config.Experimental.Backend.Armeria => buildArmeriaServer(routes, port, secure, hsts, networking, debugHttp)
  }
  private def createStatsdConfig(metricsConfig: Config.Metrics): StatsDMetricFactoryConfig = {
    val server = InetSocketAddress.createUnresolved(metricsConfig.statsd.hostname, metricsConfig.statsd.port)
    val tags   = metricsConfig.statsd.tags.toVector.map { case (name, value) => Tag.of(name, value) }
    StatsDMetricFactoryConfig(Some(metricsConfig.statsd.prefix), server, defaultTags = tags)
  }

  private[core] def hstsMiddleware[F[_]: Async](hsts: Config.HSTS, routes: HttpApp[F]): HttpApp[F] =
    if (hsts.enable)
      HSTS(routes, `Strict-Transport-Security`.unsafeFromDuration(hsts.maxAge))
    else routes

  private def loggerMiddleware[F[_]: Async](routes: HttpApp[F], config: Config.Debug.Http): HttpApp[F] =
    if (config.enable) {
      LoggerMiddleware.httpApp[F](
        logHeaders        = config.logHeaders,
        logBody           = config.logBody,
        redactHeadersWhen = config.redactHeaders.map(CIString(_)).contains(_),
        logAction         = Some((msg: String) => Logger[F].debug(msg))
      )(routes)
    } else routes

  private def timeoutMiddleware[F[_]: Async](routes: HttpApp[F], networking: Config.Networking): HttpApp[F] =
    Timeout.httpApp[F](timeout = networking.responseHeaderTimeout)(routes)

  private def buildBlazeServer[F[_]: Async](
    routes: HttpRoutes[F],
    port: Int,
    secure: Boolean,
    hsts: Config.HSTS,
    networking: Config.Networking,
    debugHttp: Config.Debug.Http
  ): Resource[F, Server] =
    Resource.eval(Logger[F].info("Building Blaze server")) >>
      BlazeServerBuilder[F]
        .bindSocketAddress(new InetSocketAddress(port))
        .withHttpApp(
          loggerMiddleware(timeoutMiddleware(hstsMiddleware(hsts, routes.orNotFound), networking), debugHttp)
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

  private def buildEmberServer[F[_]: Async: Network](
    routes: HttpRoutes[F],
    port: Int,
    secure: Boolean,
    hsts: Config.HSTS,
    networking: Config.Networking,
    debugHttp: Config.Debug.Http
  ): Resource[F, Server] =
    Resource.eval(TLSContext.Builder.forAsync[F].system).flatMap { tls =>
      Resource.eval(Logger[F].info("Building Ember server")) >>
        EmberServerBuilder
          .default[F]
          .withHost(ipv4"0.0.0.0")
          .withPort(Port.fromInt(port).getOrElse(port"9090"))
          .withHttpApp(
            loggerMiddleware(timeoutMiddleware(hstsMiddleware(hsts, routes.orNotFound), networking), debugHttp)
          )
          .withMaxConnections(networking.maxConnections)
          .withMaxHeaderSize(networking.maxHeadersLength)
          .withIdleTimeout(networking.idleTimeout)
          .cond(secure, _.withTLS(tls))
          .build
    }

  private def buildNettyServer[F[_]: Async](
    routes: HttpRoutes[F],
    port: Int,
    secure: Boolean,
    hsts: Config.HSTS,
    networking: Config.Networking,
    debugHttp: Config.Debug.Http
  ): Resource[F, Server] =
    Resource.eval(Logger[F].info(s"Building Netty server")) >>
      NettyServerBuilder[F]
        .bindHttp(port, "0.0.0.0")
        .withHttpApp(
          loggerMiddleware(timeoutMiddleware(hstsMiddleware(hsts, routes.orNotFound), networking), debugHttp)
        )
        .withIdleTimeout(networking.idleTimeout)
        .withMaxInitialLineLength(networking.maxRequestLineLength)
        .withMaxHeaderSize(networking.maxHeadersLength)
        .cond(
          secure,
          _.withSslContext(
            sslContext = new JdkSslContext(
              SSLContext.getDefault,
              false,
              null,
              IdentityCipherSuiteFilter.INSTANCE_DEFAULTING_TO_SUPPORTED_CIPHERS,
              ApplicationProtocolConfig.DISABLED,
              ClientAuth.OPTIONAL,
              null,
              false
            )
          )
        )
        .resource

  private def buildArmeriaServer[F[_]: Async](
    routes: HttpRoutes[F],
    port: Int,
    secure: Boolean,
    hsts: Config.HSTS,
    networking: Config.Networking,
    debugHttp: Config.Debug.Http
  ): Resource[F, Server] = {
    case class ArmeriaTlsConfig private (ksType: String, ksPath: String, ksPass: String)
    object ArmeriaTlsConfig {
      def from(
        props: Properties
      ): F[ArmeriaTlsConfig] =
        (for {
          t    <- Option(props.getProperty("javax.net.ssl.keyStoreType")).orElse(Some("PKCS12"))
          cert <- Option(props.getProperty("javax.net.ssl.keyStore"))
          pass <- Option(props.getProperty("javax.net.ssl.keyStorePassword"))
        } yield Async[F].delay(ArmeriaTlsConfig(t, cert, pass))).getOrElse(
          Async[F].raiseError(
            new IllegalStateException(
              "Invalid SSL configuration. Missing required JSSE options. See: https://docs.snowplow.io/docs/pipeline-components-and-applications/stream-collector/configure/#tls-port-binding-and-certificate-240"
            )
          )
        )
    }

    def mkTls(secure: Boolean): Resource[F, Option[KeyManagerFactory]] =
      if (secure) {
        for {
          tlsConfig <- Resource.eval(ArmeriaTlsConfig.from(System.getProperties()))
          kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
          ks  = KeyStore.getInstance(tlsConfig.ksType)
          _ <- Resource.eval(Async[F].delay(ks.load(new FileInputStream(tlsConfig.ksPath), tlsConfig.ksPass.toArray)))
          _ <- Resource.eval(Async[F].delay(kmf.init(ks, tlsConfig.ksPass.toArray)))
        } yield Some(kmf)
      } else Resource.pure(None)

    for {
      _   <- Resource.eval(Logger[F].info(s"Building Armeria server"))
      kmf <- mkTls(secure)
      server <- ArmeriaServerBuilder[F]
        .cond(!secure, _.withHttp(port))
        .cond(secure, _.withHttps(port))
        .withHttpApp(
          "/",
          loggerMiddleware(timeoutMiddleware(hstsMiddleware(hsts, routes.orNotFound), networking), debugHttp)
        )
        .cond(secure && kmf.isDefined, _.withTls(kmf.get))
        .withIdleTimeout(networking.idleTimeout)
        .withRequestTimeout(networking.responseHeaderTimeout)
        .resource
    } yield server
  }

  implicit class ConditionalAction[A](item: A) {
    def cond(cond: Boolean, action: A => A): A =
      if (cond) action(item) else item
  }
}
