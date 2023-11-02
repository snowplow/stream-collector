/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
  */
package com.snowplowanalytics.snowplow.collector.core

import cats.effect.{ExitCode, IO}
import cats.effect.kernel.Resource

import com.monovore.decline.effect.CommandIOApp
import com.monovore.decline.Opts

import io.circe.Decoder

import com.snowplowanalytics.snowplow.scalatracker.emitters.http4s.ceTracking

import com.snowplowanalytics.snowplow.collector.core.model.Sinks

abstract class App[SinkConfig: Decoder](appInfo: AppInfo)
    extends CommandIOApp(
      name    = App.helpCommand(appInfo),
      header  = "Snowplow application that collects tracking events",
      version = appInfo.version
    ) {

  def mkSinks(config: Config.Streams[SinkConfig]): Resource[IO, Sinks[IO]]

  def telemetryInfo(config: Config.Streams[SinkConfig]): IO[Telemetry.TelemetryInfo]

  final def main: Opts[IO[ExitCode]] = Run.fromCli[IO, SinkConfig](appInfo, mkSinks, telemetryInfo)
}

object App {
  private def helpCommand(appInfo: AppInfo) = s"docker run ${appInfo.dockerAlias}"
}
