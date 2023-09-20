/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
  */
package com.snowplowanalytics.snowplow.collectors.scalastream.it.kafka

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect

import com.snowplowanalytics.snowplow.collectors.scalastream.it.EventGenerator
import com.snowplowanalytics.snowplow.collectors.scalastream.it.utils._

import org.specs2.mutable.Specification

class KafkaCollectorSpec extends Specification with CatsEffect {

  override protected val Timeout = 5.minutes

  val maxBytes = 10000

  "emit the correct number of collector payloads and bad rows" in {
    val testName = "count"
    val nbGood = 1000
    val nbBad = 10
    val goodTopic = "test-raw"
    val badTopic = "test-bad"

    Containers.createContainers(
      goodTopic = goodTopic,
      badTopic = badTopic,
      maxBytes = maxBytes
    ).use { collector =>
      for {
        _ <- log(testName, "Sending data")
        _ <- EventGenerator.sendEvents(
          collector.host,
          collector.port,
          nbGood,
          nbBad,
          maxBytes
        )
        _ <- log(testName, "Data sent. Waiting for collector to work")
        _ <- IO.sleep(30.second)
        _ <- log(testName, "Consuming collector's output")
        collectorOutput <- KafkaUtils.readOutput(
          brokerAddr = s"localhost:${Containers.brokerExternalPort}",
          goodTopic = goodTopic,
          badTopic = badTopic
        )
      } yield {
        collectorOutput.good.size must beEqualTo(nbGood)
        collectorOutput.bad.size must beEqualTo(nbBad)
      }
    }
  }

}
