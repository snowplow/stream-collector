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

import scala.concurrent.duration._

import cats.effect.{ExitCode, IO}
import cats.effect.kernel.Resource
import cats.effect.metrics.CpuStarvationWarningMetrics

import com.monovore.decline.effect.CommandIOApp
import com.monovore.decline.Opts

import io.circe.Decoder

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import com.snowplowanalytics.snowplow.scalatracker.emitters.http4s.ceTracking

import com.snowplowanalytics.snowplow.collector.core.model.Sinks

abstract class App[SinkConfig: Decoder](appInfo: AppInfo)
    extends CommandIOApp(
      name    = App.helpCommand(appInfo),
      header  = "Snowplow application that collects tracking events",
      version = appInfo.version
    ) {

  implicit private val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  override def runtimeConfig =
    super.runtimeConfig.copy(cpuStarvationCheckInterval = 10.seconds)

  override def onCpuStarvationWarn(metrics: CpuStarvationWarningMetrics): IO[Unit] =
    Logger[IO].debug(
      s"Cats Effect measured responsiveness in excess of ${metrics.starvationInterval * metrics.starvationThreshold}"
    )

  def mkSinks(config: Config.Streams[SinkConfig]): Resource[IO, Sinks[IO]]

  def telemetryInfo(config: Config.Streams[SinkConfig]): IO[Telemetry.TelemetryInfo]

  final def main: Opts[IO[ExitCode]] = Run.fromCli[IO, SinkConfig](appInfo, mkSinks, telemetryInfo)
}

object App {
  private def helpCommand(appInfo: AppInfo) = s"docker run ${appInfo.dockerAlias}"
}
