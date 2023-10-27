/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
  */
package com.snowplowanalytics.snowplow.collectors.scalastream

import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.Sink

import scala.collection.mutable.ListBuffer

// Allow the testing framework to test collection events using the
// same methods from AbstractSink as the other sinks.
class TestSink extends Sink {

  override val maxBytes = Int.MaxValue

  private val buf: ListBuffer[Array[Byte]] = ListBuffer()
  def storedRawEvents: List[Array[Byte]]   = buf.toList

  override def storeRawEvents(events: List[Array[Byte]], key: String): Unit =
    buf ++= events

  override def shutdown(): Unit = ()
}
