/*
 * Copyright (c) 2013-2021 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream
package sinks

import com.snowplowanalytics.snowplow.collectors.scalastream.model._
import org.apache.pulsar.client.api.Producer
import org.apache.pulsar.client.api.PulsarClient

/**
  * Pulsar Sink for the Scala Stream Collector
  */
class PulsarSink(
  pulsarConfig: Pulsar,
  topicName: String
) extends Sink {

  // Records must not exceed MaxBytes - 1MB
  override val MaxBytes = 1000000

  private val pulsarProducer = createProducer

  /**
    * Creates a new Pulsar Producer with the given
    * configuration options
    *
    * @return a new Pulsar Producer
    */
  private def createProducer: Producer[Array[Byte]] = {

    log.info(s"Create Pulsar Producer to brokers: ${pulsarConfig.brokers}")

    val client = PulsarClient.builder().serviceUrl(pulsarConfig.brokers).build();

    client.newProducer().topic(topicName).create()
  }

  /**
    * Store raw events to the topic
    *
    * @param events The list of events to send
    * @param key The partition key to use
    */
  override def storeRawEvents(events: List[Array[Byte]], key: String): List[Array[Byte]] = {
    log.debug(s"Writing ${events.size} Thrift records to Pulsar topic $topicName at key $key")
    events.foreach { event =>
      pulsarProducer.newMessage().key(key).value(event).send()
    }
    Nil
  }
}
