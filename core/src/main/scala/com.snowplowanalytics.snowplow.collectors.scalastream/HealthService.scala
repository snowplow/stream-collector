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
