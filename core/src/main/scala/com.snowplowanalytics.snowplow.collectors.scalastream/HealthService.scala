/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Snowplow Community License Version 1.0,
 * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
 * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
 */
package com.snowplowanalytics.snowplow.collectors.scalastream

trait HealthService {
  def isHealthy: Boolean
}

object HealthService {

  class Settable extends HealthService {
    @volatile private var state: Boolean = false

    override def isHealthy: Boolean = state

    def toUnhealthy(): Unit =
      state = false

    def toHealthy(): Unit =
      state = true
  }

}
