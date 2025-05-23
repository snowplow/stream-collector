/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This software is made available by Snowplow Analytics, Ltd.,
  * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
  * located at https://docs.snowplow.io/limited-use-license-1.1
  * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
  * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
  */
package com.snowplowanalytics.snowplow.collector.core

trait Sink[F[_]] {

  // Maximum number of bytes that a single record can contain.
  // If a record is bigger, a size violation bad row is emitted instead
  val maxBytes: Int

  def isHealthy: F[Boolean]

  /** Write a batch of messages into the stream
    *
    *  The `Sink` is expected to write the messages immediately with minimal delay. The core
    *  collector has already batched up messages into a sensible sized batch.
    *
    *  The returned `F[Unit]` must complete immediately, e.g. the `Sink` should start the work on
    *  a fiber.
    *
    *  The `Sink` must handle all failures by retrying the write.  The returned `F[Unit]` must not
    *  fail.
    */
  def storeRawEvents(events: List[Array[Byte]]): F[Unit]
}
