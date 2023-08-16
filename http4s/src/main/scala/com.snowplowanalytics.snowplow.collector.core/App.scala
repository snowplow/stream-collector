package com.snowplowanalytics.snowplow.collector.core

import cats.effect.{ExitCode, IO, Sync}
import cats.effect.kernel.Resource

import com.monovore.decline.effect.CommandIOApp
import com.monovore.decline.Opts

import io.circe.Decoder

import com.snowplowanalytics.snowplow.collector.core.model.Sinks

abstract class App[SinkConfig <: Config.Sink: Decoder](appInfo: AppInfo)
    extends CommandIOApp(
      name    = App.helpCommand(appInfo),
      header  = "Snowplow application that collects tracking events",
      version = appInfo.version
    ) {

  def mkSinks[F[_]: Sync](config: Config.Streams[SinkConfig]): Resource[F, Sinks[F]]

  final def main: Opts[IO[ExitCode]] = Run.fromCli[IO, SinkConfig](appInfo, mkSinks)
}

object App {
  private def helpCommand(appInfo: AppInfo) = s"docker run ${appInfo.dockerAlias}"
}
