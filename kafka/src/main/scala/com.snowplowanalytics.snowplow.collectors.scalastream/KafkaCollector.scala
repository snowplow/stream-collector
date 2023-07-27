/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Snowplow Community License Version 1.0,
 * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
 * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
 */
package com.snowplowanalytics.snowplow.collectors.scalastream

import com.snowplowanalytics.snowplow.collectors.scalastream.model._
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.KafkaSink
import com.snowplowanalytics.snowplow.collectors.scalastream.telemetry.TelemetryAkkaService
import com.snowplowanalytics.snowplow.collectors.scalastream.generated.BuildInfo

object KafkaCollector extends Collector {
  def appName      = BuildInfo.shortName
  def appVersion   = BuildInfo.version
  def scalaVersion = BuildInfo.scalaVersion

  def main(args: Array[String]): Unit = {
    val (collectorConf, akkaConf) = parseConfig(args)
    val telemetry                 = TelemetryAkkaService.initWithCollector(collectorConf, BuildInfo.moduleName, appVersion)
    val sinks = {
      val goodStream = collectorConf.streams.good
      val badStream  = collectorConf.streams.bad
      val bufferConf = collectorConf.streams.buffer
      val (good, bad) = collectorConf.streams.sink match {
        case kc: Kafka =>
          (
            new KafkaSink(kc.maxBytes, kc, bufferConf, goodStream),
            new KafkaSink(kc.maxBytes, kc, bufferConf, badStream)
          )
        case _ => throw new IllegalArgumentException("Configured sink is not Kafka")
      }
      CollectorSinks(good, bad)
    }
    run(collectorConf, akkaConf, sinks, telemetry)
  }
}
