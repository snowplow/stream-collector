package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.implicits._
import cats.effect.{Async, ExitCode, Sync}
import cats.effect.kernel.Resource
import fs2.io.net.Network
import com.comcast.ip4s.IpLiteralSyntax
import org.http4s.HttpApp
import org.http4s.server.Server
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.netty.server.NettyServerBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.net.InetSocketAddress
import scala.concurrent.duration.{DurationLong, FiniteDuration}

import com.snowplowanalytics.snowplow.collectors.scalastream.model._

object CollectorApp {

  implicit private def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  def run[F[_]: Async](
    mkGood: Resource[F, Sink[F]],
    mkBad: Resource[F, Sink[F]],
    config: CollectorConfig,
    appName: String,
    appVersion: String
  ): F[ExitCode] = {
    val resources = for {
      bad  <- mkBad
      good <- mkGood
      _ <- withGracefulShutdown(610.seconds) {
        val sinks                                 = CollectorSinks(good, bad)
        val collectorService: CollectorService[F] = new CollectorService[F](config, sinks, appName, appVersion)
        buildHttpServer[F](new CollectorRoutes[F](collectorService).value)
      }
    } yield ()

    resources.surround(Async[F].never[ExitCode])
  }

  private def withGracefulShutdown[F[_]: Async, A](delay: FiniteDuration)(resource: Resource[F, A]): Resource[F, A] =
    for {
      a <- resource
      _ <- Resource.onFinalizeCase {
        case Resource.ExitCase.Canceled =>
          Logger[F].warn(s"Shutdown interrupted. Will continue to serve requests for $delay") >>
            Async[F].sleep(delay)
        case _ =>
          Async[F].unit
      }
    } yield a

  private def buildHttpServer[F[_]: Async](app: HttpApp[F]): Resource[F, Server] =
    sys.env.get("HTTP4S_BACKEND").map(_.toUpperCase()) match {
      case Some("EMBER") | None => buildEmberServer[F](app)
      case Some("BLAZE") => buildBlazeServer[F](app)
      case Some("NETTY") => buildNettyServer[F](app)
      case Some(other)   => throw new IllegalArgumentException(s"Unrecognized http4s backend $other")
    }

  private def buildEmberServer[F[_]: Async](app: HttpApp[F]) = {
    implicit val network = Network.forAsync[F]
    Resource.eval(Logger[F].info("Building ember server")) >>
      EmberServerBuilder
        .default[F]
        .withHost(ipv4"0.0.0.0")
        .withPort(port"8080")
        .withHttpApp(app)
        .withIdleTimeout(610.seconds)
        .build
  }

  private def buildBlazeServer[F[_]: Async](app: HttpApp[F]): Resource[F, Server] =
    Resource.eval(Logger[F].info("Building blaze server")) >>
      BlazeServerBuilder[F]
        .bindSocketAddress(new InetSocketAddress(8080))
        .withHttpApp(app)
        .withIdleTimeout(610.seconds)
        .resource

  private def buildNettyServer[F[_]: Async](app: HttpApp[F]): Resource[F, Server] =
    Resource.eval(Logger[F].info("Building netty server")) >>
      NettyServerBuilder[F].bindLocal(8080).withHttpApp(app).withIdleTimeout(610.seconds).resource
}
