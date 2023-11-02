/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
  */
package com.snowplowanalytics.snowplow.collector.core

trait Sink[F[_]] {

  // Maximum number of bytes that a single record can contain.
  // If a record is bigger, a size violation bad row is emitted instead
  val maxBytes: Int

  def isHealthy: F[Boolean]
  def storeRawEvents(events: List[Array[Byte]], key: String): F[Unit]
}
