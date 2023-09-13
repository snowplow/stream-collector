package com.snowplowanalytics.snowplow.collector.core

import java.nio.file.Path

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.FiniteDuration

import cats.implicits._
import cats.data.EitherT

import cats.effect.{Async, ExitCode, Sync}
import cats.effect.kernel.Resource

import org.http4s.blaze.client.BlazeClientBuilder

import com.monovore.decline.Opts

import io.circe.Decoder

import com.snowplowanalytics.snowplow.scalatracker.Tracking

import com.snowplowanalytics.snowplow.collector.core.model.Sinks

object Run {

  implicit private def logger[F[_]: Sync] = Slf4jLogger.getLogger[F]

  def fromCli[F[_]: Async: Tracking, SinkConfig: Decoder](
    appInfo: AppInfo,
    mkSinks: Config.Streams[SinkConfig] => Resource[F, Sinks[F]],
    telemetryInfo: Config[SinkConfig]   => Telemetry.TelemetryInfo
  ): Opts[F[ExitCode]] = {
    val configPath = Opts.option[Path]("config", "Path to HOCON configuration (optional)", "c", "config.hocon").orNone
    configPath.map(fromPath[F, SinkConfig](appInfo, mkSinks, telemetryInfo, _))
  }

  private def fromPath[F[_]: Async: Tracking, SinkConfig: Decoder](
    appInfo: AppInfo,
    mkSinks: Config.Streams[SinkConfig] => Resource[F, Sinks[F]],
    telemetryInfo: Config[SinkConfig]   => Telemetry.TelemetryInfo,
    path: Option[Path]
  ): F[ExitCode] = {
    val eitherT = for {
      config <- ConfigParser.fromPath[F, SinkConfig](path)
      _      <- EitherT.right[ExitCode](fromConfig(appInfo, mkSinks, config, telemetryInfo))
    } yield ExitCode.Success

    eitherT.merge.handleErrorWith { e =>
      Logger[F].error(e)("Exiting") >>
        prettyLogException(e).as(ExitCode.Error)
    }
  }

  private def fromConfig[F[_]: Async: Tracking, SinkConfig](
    appInfo: AppInfo,
    mkSinks: Config.Streams[SinkConfig] => Resource[F, Sinks[F]],
    config: Config[SinkConfig],
    telemetryInfo: Config[SinkConfig] => Telemetry.TelemetryInfo
  ): F[ExitCode] = {
    val resources = for {
      sinks <- mkSinks(config.streams)
      collectorService = new Service[F](
        config,
        Sinks(sinks.good, sinks.bad),
        appInfo
      )
      httpServer = HttpServer.build[F](
        new Routes[F](config.enableDefaultRedirect, collectorService).value,
        config.interface,
        if (config.ssl.enable) config.ssl.port else config.port,
        config.ssl.enable
      )
      _          <- withGracefulShutdown(config.preTerminationPeriod)(httpServer)
      httpClient <- BlazeClientBuilder[F].resource
    } yield httpClient

    resources.use { httpClient =>
      Telemetry
        .run(
          config.telemetry,
          httpClient,
          appInfo,
          telemetryInfo(config).region,
          telemetryInfo(config).cloud
        )
        .compile
        .drain
        .flatMap(_ => Async[F].never[ExitCode])
    }
  }

  private def prettyLogException[F[_]: Sync](e: Throwable): F[Unit] = {

    def logCause(e: Throwable): F[Unit] =
      Option(e.getCause) match {
        case Some(e) => Logger[F].error(s"caused by: ${e.getMessage}") >> logCause(e)
        case None    => Sync[F].unit
      }

    Logger[F].error(e.getMessage) >> logCause(e)
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
}
