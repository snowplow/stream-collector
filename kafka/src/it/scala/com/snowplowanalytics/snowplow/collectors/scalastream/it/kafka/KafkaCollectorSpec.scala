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
