package com.snowplowanalytics.snowplow.collectors.scalastream

import com.snowplowanalytics.snowplow.collectors.scalastream.model.CollectorConfig

object TestUtils {

  val testConf = CollectorConfig(
    paths = Map(
      "/com.acme/track"    -> "/com.snowplowanalytics.snowplow/tp2",
      "/com.acme/redirect" -> "/r/tp2",
      "/com.acme/iglu"     -> "/com.snowplowanalytics.iglu/v1"
    )
  )
}
