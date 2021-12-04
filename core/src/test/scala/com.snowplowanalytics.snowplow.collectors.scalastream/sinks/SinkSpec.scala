/*
 * Copyright (c) 2013-2021 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, and
 * you may not use this file except in compliance with the Apache License
 * Version 2.0.  You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Apache License Version 2.0 is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import cats.Id

import com.snowplowanalytics.snowplow.collectors.scalastream.model.BufferConfig

import scala.collection.mutable.ListBuffer

import org.specs2.mutable.Specification

class SinkSpec extends Specification {

  /** A sink that immediately calls the onComplete callback */
  class HealthySink(buf: ListBuffer[Array[Byte]], throttler: Sink.Throttler) extends Sink.Throttled(throttler) {
    val MaxBytes = Int.MaxValue
    override def storeRawEventsThrottled(events: List[Array[Byte]], key: String): Unit = {
      buf ++= events
      onComplete(events.foldLeft(0L)(_ + _.size))
    }
  }

  /** A sink that buffers, but never calls the onComplete callback */
  class UnhealthySink(buf: ListBuffer[Array[Byte]], throttler: Sink.Throttler) extends Sink.Throttled(throttler) {
    val MaxBytes = Int.MaxValue
    override def storeRawEventsThrottled(events: List[Array[Byte]], key: String): Unit =
      buf ++= events
  }

  // A 64 byte event.
  val testEvent = (1 to 64).map(_ => 'a'.toByte).toArray

  // A config that allows 2 * 64 testEvents
  val config = BufferConfig(
    byteLimit      = 1000,
    recordLimit    = 1000,
    timeLimit      = 1000,
    hardByteLimit  = Some(128),
    enqueueTimeout = 2
  )

  "The throttled sink" should {
    "immediately sink events to a healthy sink" in {
      val buf  = ListBuffer[Array[Byte]]()
      val sink = Sink.throttled[Id](config, new HealthySink(buf, _), new HealthySink(buf, _))

      val results = (1 to 10).toList.map { _ =>
        sink.good.storeRawEvents(List(testEvent), "key")
      }

      results must contain(beTrue).foreach

      buf must have size 10
    }

    "something else" in {
      val buf  = ListBuffer[Array[Byte]]()
      val sink = Sink.throttled[Id](config, new UnhealthySink(buf, _), new UnhealthySink(buf, _))

      val results = (1 to 4).toList.map { _ =>
        sink.good.storeRawEvents(List(testEvent), "key")
      }

      results must containTheSameElementsAs(Seq(true, true, false, false))

      buf must have size 2
    }
  }
}
