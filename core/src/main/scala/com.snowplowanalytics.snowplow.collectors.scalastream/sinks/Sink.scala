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

import org.slf4j.LoggerFactory

// Define an interface for all sinks to use to store events.
trait Sink {

  // Maximum number of bytes that a single record can contain.
  // If a record is bigger, a size violation bad row is emitted instead
  val maxBytes: Int

  lazy val log = LoggerFactory.getLogger(getClass())

  def isHealthy: Boolean = true
  def storeRawEvents(events: List[Array[Byte]], key: String): Unit
  def shutdown(): Unit
}
