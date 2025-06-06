/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This software is made available by Snowplow Analytics, Ltd.,
  * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
  * located at https://docs.snowplow.io/limited-use-license-1.1
  * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
  * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
  */
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

  type MkSinks[F[_], SinkConfig] = Config.Streams[SinkConfig] => Resource[F, Sinks[F]]

  type TelemetryInfo[F[_], SinkConfig] = Config.Streams[SinkConfig] => F[Telemetry.TelemetryInfo]

  implicit private def logger[F[_]: Sync]: Logger[F] = Slf4jLogger.getLogger[F]

  def fromCli[F[_]: Async: Tracking, SinkConfig: Decoder](
    appInfo: AppInfo,
    mkSinks: MkSinks[F, SinkConfig],
    telemetryInfo: TelemetryInfo[F, SinkConfig]
  ): Opts[F[ExitCode]] = {
    val configPath = Opts.option[Path]("config", "Path to HOCON configuration (optional)", "c", "config.hocon").orNone
    configPath.map(fromPath[F, SinkConfig](appInfo, mkSinks, telemetryInfo, _))
  }

  private def fromPath[F[_]: Async: Tracking, SinkConfig: Decoder](
    appInfo: AppInfo,
    mkSinks: MkSinks[F, SinkConfig],
    telemetryInfo: TelemetryInfo[F, SinkConfig],
    path: Option[Path]
  ): F[ExitCode] = {
    val eitherT = for {
      config <- ConfigParser.fromPath[F, SinkConfig](path)
      _      <- checkLicense(config.license.accept)
      _      <- EitherT.right[ExitCode](fromConfig(appInfo, mkSinks, telemetryInfo, config))
    } yield ExitCode.Success

    eitherT.merge.handleErrorWith { e =>
      Logger[F].error(e)("Exiting") >>
        prettyLogException(e).as(ExitCode.Error)
    }
  }

  private def checkLicense[F[_]: Sync](acceptLicense: Boolean): EitherT[F, ExitCode, _] =
    EitherT.liftF {
      if (acceptLicense)
        Sync[F].unit
      else
        Sync[F].raiseError(
          new IllegalStateException(
            "Please accept the terms of the Snowplow Limited Use License Agreement to proceed. See https://docs.snowplow.io/docs/pipeline-components-and-applications/stream-collector/configure/#license for more information on the license and how to configure this."
          )
        )
    }

  private def fromConfig[F[_]: Async: Tracking, SinkConfig](
    appInfo: AppInfo,
    mkSinks: MkSinks[F, SinkConfig],
    telemetryInfo: TelemetryInfo[F, SinkConfig],
    config: Config[SinkConfig]
  ): F[ExitCode] = {
    val resources = for {
      sinks <- mkSinks(config.streams)
      collectorService = new Service[F](
        config,
        Sinks(sinks.good, sinks.bad),
        appInfo
      )
      routes = new Routes[F](
        config.enableDefaultRedirect,
        config.rootResponse.enabled,
        config.crossDomain.enabled,
        collectorService
      )
      httpServer = HttpServer.build[F](
        routes.value,
        routes.health,
        if (config.ssl.enable) config.ssl.port else config.port,
        config.ssl.enable,
        config.hsts,
        config.networking,
        config.monitoring.metrics
      )(HttpServer.buildBlazeServer)
      _          <- withGracefulShutdown(config.preTerminationPeriod)(httpServer)
      httpClient <- BlazeClientBuilder[F].resource
    } yield httpClient

    resources.use { httpClient =>
      val appId = java.util.UUID.randomUUID.toString
      Telemetry
        .run(config.telemetry, httpClient, appInfo, appId, telemetryInfo(config.streams))
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
