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
