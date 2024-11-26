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
package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import io.circe.Decoder
import io.circe.generic.semiauto._

final case class NsqSinkConfig(
  maxBytes: Int,
  threadPoolSize: Int,
  host: String,
  port: Int
)

object NsqSinkConfig {
  implicit val configDecoder: Decoder[NsqSinkConfig] = deriveDecoder[NsqSinkConfig]
}
