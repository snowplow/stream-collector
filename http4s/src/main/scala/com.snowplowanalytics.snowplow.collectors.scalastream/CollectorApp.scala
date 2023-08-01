package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.implicits._
import cats.effect.{ExitCode, IO}
import cats.effect.kernel.Resource
import com.comcast.ip4s.IpLiteralSyntax
import org.http4s.server.Server
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.netty.server.NettyServerBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.net.InetSocketAddress
import scala.concurrent.duration.DurationLong

object CollectorApp {

  implicit private def unsafeLogger: Logger[IO] =
    Slf4jLogger.getLogger[IO]

  def run(): IO[ExitCode] =
    buildHttpServer().use(_ => IO.never).as(ExitCode.Success)

  private def buildHttpServer(): Resource[IO, Server] =
    sys.env.get("HTTP4S_BACKEND").map(_.toUpperCase()) match {
      case Some("EMBER") | None => buildEmberServer
      case Some("BLAZE") => buildBlazeServer
      case Some("NETTY") => buildNettyServer
      case Some(other)   => throw new IllegalArgumentException(s"Unrecognized http4s backend $other")
    }

  private def buildEmberServer =
    Resource.eval(Logger[IO].info("Building ember server")) >>
      EmberServerBuilder
        .default[IO]
        .withHost(ipv4"0.0.0.0")
        .withPort(port"8080")
        .withHttpApp(new CollectorRoutes[IO].value)
        .withIdleTimeout(610.seconds)
        .build

  private def buildBlazeServer: Resource[IO, Server] =
    Resource.eval(Logger[IO].info("Building blaze server")) >>
      BlazeServerBuilder[IO]
        .bindSocketAddress(new InetSocketAddress(8080))
        .withHttpApp(new CollectorRoutes[IO].value)
        .withIdleTimeout(610.seconds)
        .resource

  private def buildNettyServer: Resource[IO, Server] =
    Resource.eval(Logger[IO].info("Building netty server")) >>
      NettyServerBuilder[IO]
        .bindLocal(8080)
        .withHttpApp(new CollectorRoutes[IO].value)
        .withIdleTimeout(610.seconds)
        .resource
}
