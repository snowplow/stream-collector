/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.0
 * located at https://docs.snowplow.io/limited-use-license-1.0
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream.it.kafka

import cats.effect._
import org.apache.kafka.clients.consumer._
import java.util.Properties
import java.time.Duration
import scala.jdk.CollectionConverters._
import com.snowplowanalytics.snowplow.collectors.scalastream.it.utils._
import com.snowplowanalytics.snowplow.collectors.scalastream.it.CollectorOutput

object KafkaUtils {

  def readOutput(
    brokerAddr: String,
    goodTopic: String,
    badTopic: String
  ): IO[CollectorOutput] = {
    createConsumer(brokerAddr).use { kafkaConsumer =>
      IO {
        kafkaConsumer.subscribe(List(goodTopic, badTopic).asJava)
        val records = kafkaConsumer.poll(Duration.ofSeconds(20))
        val extract = (r: ConsumerRecords[String, Array[Byte]], topicName: String) =>
          r.records(topicName).asScala.toList.map(_.value())
        val goodCount = extract(records, goodTopic).map(parseCollectorPayload)
        val badCount = extract(records, badTopic).map(parseBadRow)
        CollectorOutput(goodCount, badCount)
      }
    }
  }

  private def createConsumer(brokerAddr: String): Resource[IO, KafkaConsumer[String, Array[Byte]]] = {
    val acquire = IO {
      val props = new Properties()
      props.setProperty("bootstrap.servers", brokerAddr)
      props.setProperty("group.id", "it-collector")
      props.setProperty("auto.offset.reset", "earliest")
      props.setProperty("max.poll.records", "2000")
      props.setProperty("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
      props.setProperty("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer")
      new KafkaConsumer[String, Array[Byte]](props)
    }
    val release = (p: KafkaConsumer[String, Array[Byte]]) => IO(p.close())
    Resource.make(acquire)(release)
  }

}
