/*
 * Copyright (c) 2013-2021 Snowplow Analytics Ltd.
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
package sinks

import java.util.concurrent.{Semaphore, TimeUnit}

import cats.Monad
import cats.implicits._

import org.slf4j.LoggerFactory

import com.snowplowanalytics.snowplow.collectors.scalastream.model.{BufferConfig, CollectorSinks}

// Define an interface for all sinks to use to store events.
trait Sink {

  // Maximum number of bytes that a single record can contain
  val MaxBytes: Int

  lazy val log = LoggerFactory.getLogger(getClass())

  def isHealthy: Boolean = true

  /** Store the raw events in the output sink
    *  @param events The events to store
    *  @return whether the events were stored successfully
    */
  def storeRawEvents(events: List[Array[Byte]], key: String): Boolean
}

object Sink {
  abstract class Throttled(throttler: Throttler) extends Sink {

    def storeRawEventsThrottled(events: List[Array[Byte]], key: String): Unit

    protected def onComplete(sunkBytes: Long): Unit =
      throttler.release(sunkBytes)

    override def storeRawEvents(events: List[Array[Byte]], key: String): Boolean = {
      val bytes = events.foldLeft(0L)(_ + _.size.toLong)
      if (throttler.tryAcquire(bytes)) {
        storeRawEventsThrottled(events, key)
        true
      } else
        false
    }
  }

  case class Throttler(tryAcquire: Long => Boolean, release: Long => Unit)

  def throttled[F[_]: Monad](
    config: BufferConfig,
    buildGood: Throttler => F[Sink],
    buildBad: Throttler  => F[Sink]
  ): F[CollectorSinks] = {
    val semaphore = new Semaphore(bytesToPermits(config.hardByteLimit.getOrElse(Runtime.getRuntime.maxMemory / 4)))
    val throttler = Throttler(
      b => semaphore.tryAcquire(bytesToPermits(b), config.enqueueTimeout, TimeUnit.MILLISECONDS),
      b => semaphore.release(bytesToPermits(b))
    )
    for {
      good <- buildGood(throttler)
      bad  <- buildBad(throttler)
    } yield CollectorSinks(good, bad)
  }

  // 1 permit corresponds to 64 bytes.
  // Int.MaxValue permits corresponds to 127 GB, so we can accommodate any reasonable heap using a Semaphore.
  private def bytesToPermits(bytes: Long): Int =
    (bytes >> 6).toInt
}
