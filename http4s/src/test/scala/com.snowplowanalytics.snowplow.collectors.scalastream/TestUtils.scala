package com.snowplowanalytics.snowplow.collectors.scalastream

import scala.concurrent.duration._
import com.snowplowanalytics.snowplow.collectors.scalastream.model._

object TestUtils {

  val testConf = CollectorConfig(
    paths = Map(
      "/com.acme/track"    -> "/com.snowplowanalytics.snowplow/tp2",
      "/com.acme/redirect" -> "/r/tp2",
      "/com.acme/iglu"     -> "/com.snowplowanalytics.iglu/v1"
    ),
    cookie = CookieConfig(
      enabled        = true,
      name           = "sp",
      expiration     = 365.days,
      domains        = List.empty,
      fallbackDomain = None,
      secure         = false,
      httpOnly       = false,
      sameSite       = None
    ),
    cors = CORSConfig(60.seconds)
  )
}
