/*
 * Copyright (c) 2013-2022 Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache
 * License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 *
 * See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream

import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.Sink

import scala.collection.mutable.ListBuffer

// Allow the testing framework to test collection events using the
// same methods from AbstractSink as the other sinks.
class TestSink extends Sink {

  private val buf: ListBuffer[Array[Byte]] = ListBuffer()
  def storedRawEvents: List[Array[Byte]]   = buf.toList

  // Effectively no limit to the record size
  override val MaxBytes = Int.MaxValue

  override def storeRawEvents(events: List[Array[Byte]], key: String): Unit =
    buf ++= events

  override def shutdown(): Unit = ()
}
