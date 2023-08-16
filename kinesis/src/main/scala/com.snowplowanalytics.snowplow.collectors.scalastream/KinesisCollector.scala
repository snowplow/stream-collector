/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
  */
package com.snowplowanalytics.snowplow.collectors.scalastream

import java.util.concurrent.ScheduledThreadPoolExecutor
import cats.syntax.either._
import com.snowplowanalytics.snowplow.collectors.scalastream.generated.BuildInfo
import com.snowplowanalytics.snowplow.collectors.scalastream.model._
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.KinesisSink
import com.snowplowanalytics.snowplow.collectors.scalastream.telemetry.TelemetryAkkaService

object KinesisCollector extends Collector {
  def appName      = BuildInfo.shortName
  def appVersion   = BuildInfo.version
  def scalaVersion = BuildInfo.scalaVersion

  def main(args: Array[String]): Unit = {
    val (collectorConf, akkaConf) = parseConfig(args)
    val telemetry                 = TelemetryAkkaService.initWithCollector(collectorConf, BuildInfo.moduleName, appVersion)
    val sinks: Either[Throwable, CollectorSinks] = for {
      kc <- collectorConf.streams.sink match {
        case kc: Kinesis => kc.asRight
        case _           => new IllegalArgumentException("Configured sink is not Kinesis").asLeft
      }
      es         = buildExecutorService(kc)
      goodStream = collectorConf.streams.good
      badStream  = collectorConf.streams.bad
      bufferConf = collectorConf.streams.buffer
      sqsGood    = kc.sqsGoodBuffer
      sqsBad     = kc.sqsBadBuffer
      good <- KinesisSink.createAndInitialize(
        kc.maxBytes,
        kc,
        bufferConf,
        goodStream,
        sqsGood,
        es
      )
      bad <- KinesisSink.createAndInitialize(
        kc.maxBytes,
        kc,
        bufferConf,
        badStream,
        sqsBad,
        es
      )
    } yield CollectorSinks(good, bad)

    sinks match {
      case Right(s) => run(collectorConf, akkaConf, s, telemetry)
      case Left(e)  => throw e
    }
  }

  def buildExecutorService(kc: Kinesis): ScheduledThreadPoolExecutor = {
    log.info("Creating thread pool of size " + kc.threadPoolSize)
    new ScheduledThreadPoolExecutor(kc.threadPoolSize)
  }
}
