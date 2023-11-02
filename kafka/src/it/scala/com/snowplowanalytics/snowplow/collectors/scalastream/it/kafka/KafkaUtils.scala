/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
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
