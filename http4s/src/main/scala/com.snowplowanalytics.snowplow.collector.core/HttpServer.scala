package com.snowplowanalytics.snowplow.collector.core

import java.net.InetSocketAddress
import javax.net.ssl.SSLContext

import io.netty.handler.ssl._

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.DurationLong

import com.comcast.ip4s.{IpAddress, Port}

import cats.implicits._

import cats.effect.{Async, Resource}

import org.http4s.HttpApp
import org.http4s.server.Server
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.netty.server.NettyServerBuilder

import fs2.io.net.Network
import fs2.io.net.tls.TLSContext

object HttpServer {

  implicit private def logger[F[_]: Async] = Slf4jLogger.getLogger[F]

  def build[F[_]: Async](
    app: HttpApp[F],
    interface: String,
    port: Int,
    secure: Boolean
  ): Resource[F, Server] =
    sys.env.get("HTTP4S_BACKEND").map(_.toUpperCase()) match {
      case Some("EMBER") | None => buildEmberServer[F](app, interface, port, secure)
      case Some("BLAZE") => buildBlazeServer[F](app, port, secure)
      case Some("NETTY") => buildNettyServer[F](app, port, secure)
      case Some(other)   => throw new IllegalArgumentException(s"Unrecognized http4s backend $other")
    }

  private def buildEmberServer[F[_]: Async](
    app: HttpApp[F],
    interface: String,
    port: Int,
    secure: Boolean
  ) = {
    implicit val network = Network.forAsync[F]
    Resource.eval(Logger[F].info("Building ember server")) >>
      EmberServerBuilder
        .default[F]
        .withHost(IpAddress.fromString(interface).get)
        .withPort(Port.fromInt(port).get)
        .withHttpApp(app)
        .withIdleTimeout(610.seconds)
        .cond(secure, _.withTLS(TLSContext.Builder.forAsync.fromSSLContext(SSLContext.getDefault)))
        .build
  }

  private def buildBlazeServer[F[_]: Async](
    app: HttpApp[F],
    port: Int,
    secure: Boolean
  ): Resource[F, Server] =
    Resource.eval(Logger[F].info("Building blaze server")) >>
      BlazeServerBuilder[F]
        .bindSocketAddress(new InetSocketAddress(port))
        .withHttpApp(app)
        .withIdleTimeout(610.seconds)
        .cond(secure, _.withSslContext(SSLContext.getDefault))
        .resource

  private def buildNettyServer[F[_]: Async](
    app: HttpApp[F],
    port: Int,
    secure: Boolean
  ) =
    Resource.eval(Logger[F].info("Building netty server")) >>
      NettyServerBuilder[F]
        .bindLocal(port)
        .withHttpApp(app)
        .withIdleTimeout(610.seconds)
        .cond(
          secure,
          _.withSslContext(
            new JdkSslContext(
              SSLContext.getDefault,
              false,
              null,
              IdentityCipherSuiteFilter.INSTANCE,
              new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                ApplicationProtocolNames.HTTP_2,
                ApplicationProtocolNames.HTTP_1_1
              ),
              ClientAuth.NONE,
              null,
              false
            )
          )
        )
        .resource

  implicit class ConditionalAction[A](item: A) {
    def cond(cond: Boolean, action: A => A): A =
      if (cond) action(item) else item
  }
}
