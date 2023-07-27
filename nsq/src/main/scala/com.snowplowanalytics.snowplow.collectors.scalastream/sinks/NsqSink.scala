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
