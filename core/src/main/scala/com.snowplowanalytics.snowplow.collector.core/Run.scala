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

import scala.concurrent.duration.{Duration, FiniteDuration}

import cats.implicits._
import cats.data.EitherT

import cats.effect.implicits._
import cats.effect.{Async, Deferred, ExitCode, Resource, Sync}
import cats.effect.std.{Queue, QueueSink}

import org.http4s.blaze.client.BlazeClientBuilder

import com.monovore.decline.Opts

import io.circe.Decoder

import com.snowplowanalytics.snowplow.scalatracker.Tracking
import com.snowplowanalytics.snowplow.collector.thrift.CollectorPayload

import com.snowplowanalytics.snowplow.streams.http.HttpSinkConfig
import com.snowplowanalytics.snowplow.streams.http.sink.HttpSink

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

    val resource = for {
      queue    <- Resource.eval(Queue.unbounded[F, Option[CollectorPayload]])
      httpSink <- mkHttpSink(config.streams.http)
      sinks <- mkSinks(config.streams).map { s =>
        val goodSink = httpSink.getOrElse(s.good)
        s.copy(good = goodSink)
      }
      sig <- Resource.eval(Deferred[F, Throwable])
      // The dequeue loop is acquired before the http server, so that on shutdown the http server is
      // released first.  This means we stop accepting new events before we stop draining the queue.
      //
      // On release we offer `None` into the queue, which is the signal for the dequeue stream to
      // terminate.  A clean termination flushes any partially-filled batch to the sink, whereas
      // cancelling the fiber would discard it.  We then join the fiber, so that release does not
      // complete until everything left in the queue has been written to the sink.
      _ <- Resource.make(
        Sinks
          .dequeue(config, appInfo, queue, sinks)
          .compile
          .drain
          .onError {
            case t => Logger[F].error(t)("Error writing events to the sink") >> sig.complete(t).void
          }
          .start
      ) { fiber =>
        Logger[F].info("Draining the queue of pending events before shutting down the sinks") >>
          queue.offer(None) >>
          fiber.join.void >>
          Logger[F].info("Finished draining the queue of pending events")
      }
      payloadSink = (queue: QueueSink[F, Option[CollectorPayload]]).contramap[CollectorPayload](Some(_))
      _ <- runHttpServer(config, appInfo, sinks, payloadSink)
      appId = java.util.UUID.randomUUID.toString
      httpClient <- BlazeClientBuilder[F].resource
      _ <- Telemetry
        .run(config.telemetry, httpClient, appInfo, appId, telemetryInfo(config.streams))
        .mask
        .compile
        .drain
        .background
      _ <- withGracefulShutdown(config.preTerminationPeriod)
    } yield sig

    resource
      .use { sig =>
        sig.get.flatMap { t =>
          Sync[F].raiseError[Unit](t)
        }
      }
      .as(ExitCode.Success)
  }

  private def runHttpServer[F[_]: Async](
    config: Config[Any],
    appInfo: AppInfo,
    sinks: Sinks[F],
    queue: QueueSink[F, CollectorPayload]
  ): Resource[F, Unit] = {
    val collectorService = new Service[F](
      config,
      queue,
      appInfo
    )
    val routes = new Routes[F](
      config.enableDefaultRedirect,
      config.rootResponse.enabled,
      config.crossDomain.enabled,
      collectorService,
      (sinks.good.isHealthy, sinks.bad.isHealthy).mapN(_ && _)
    )
    HttpServer
      .build[F](
        routes.value,
        routes.health,
        if (config.ssl.enable) config.ssl.port else config.port,
        config.ssl.enable,
        config.hsts,
        config.networking,
        config.monitoring.metrics
      )(HttpServer.buildBlazeServer)
      .void
  }

  private def mkHttpSink[F[_]: Async](httpSinkConfig: Option[HttpSinkConfig]): Resource[F, Option[Sink[F]]] =
    httpSinkConfig match {
      case None => Resource.pure(None)
      case Some(c) =>
        HttpSink.resource(c).flatMap { sink =>
          Sink
            .ofCommonStreamsSink(
              name                       = "httpSink",
              maxBytes                   = 10000000,
              sinkRetryInterval          = Duration.Zero,
              startupHealthCheckInterval = Duration.Zero,
              sink                       = sink
            )
            .map(s => Some(s))
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

  private def withGracefulShutdown[F[_]: Async](delay: FiniteDuration): Resource[F, Unit] =
    Resource.onFinalizeCase {
      case Resource.ExitCase.Canceled =>
        Logger[F].warn(s"Shutdown interrupted. Will continue to serve requests for $delay") >>
          Async[F].sleep(delay)
      case _ =>
        Async[F].unit
    }
}
