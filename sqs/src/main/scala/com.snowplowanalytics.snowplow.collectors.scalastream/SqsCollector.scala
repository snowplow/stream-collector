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
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.SqsSink
import com.snowplowanalytics.snowplow.collectors.scalastream.telemetry.TelemetryAkkaService

object SqsCollector extends Collector {
  def appName      = BuildInfo.shortName
  def appVersion   = BuildInfo.version
  def scalaVersion = BuildInfo.scalaVersion

  def main(args: Array[String]): Unit = {
    val (collectorConf, akkaConf) = parseConfig(args)
    val telemetry                 = TelemetryAkkaService.initWithCollector(collectorConf, BuildInfo.moduleName, appVersion)
    val sinks: Either[Throwable, CollectorSinks] = for {
      sqs <- collectorConf.streams.sink match {
        case sqs: Sqs => sqs.asRight
        case sink     => new IllegalArgumentException(s"Configured sink $sink is not SQS.").asLeft
      }
      es         = new ScheduledThreadPoolExecutor(sqs.threadPoolSize)
      goodQueue  = collectorConf.streams.good
      badQueue   = collectorConf.streams.bad
      bufferConf = collectorConf.streams.buffer
      good <- SqsSink.createAndInitialize(
        sqs.maxBytes,
        sqs,
        bufferConf,
        goodQueue,
        es
      )
      bad <- SqsSink.createAndInitialize(
        sqs.maxBytes,
        sqs,
        bufferConf,
        badQueue,
        es
      )
    } yield CollectorSinks(good, bad)

    sinks match {
      case Right(s) => run(collectorConf, akkaConf, s, telemetry)
      case Left(e)  => throw e
    }
  }
}
