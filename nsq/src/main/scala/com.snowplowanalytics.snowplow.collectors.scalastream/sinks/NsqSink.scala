/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Snowplow Community License Version 1.0,
 * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
 * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
 */
package com.snowplowanalytics.snowplow.collectors.scalastream
package sinks

import scala.collection.JavaConverters._

import com.snowplowanalytics.client.nsq.NSQProducer
import com.snowplowanalytics.snowplow.collectors.scalastream.model._

/**
  * NSQ Sink for the Scala Stream Collector
  * @param nsqConfig Configuration for Nsq
  * @param topicName Nsq topic name
  */
class NsqSink(val maxBytes: Int, nsqConfig: Nsq, topicName: String) extends Sink {

  private val producer = new NSQProducer().addAddress(nsqConfig.host, nsqConfig.port).start()

  /**
    * Store raw events to the topic
    * @param events The list of events to send
    * @param key The partition key (unused)
    */
  override def storeRawEvents(events: List[Array[Byte]], key: String): Unit =
    producer.produceMulti(topicName, events.asJava)

  override def shutdown(): Unit =
    producer.shutdown()
}
