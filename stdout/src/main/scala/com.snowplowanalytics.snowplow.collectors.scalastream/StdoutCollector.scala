/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Snowplow Community License Version 1.0,
 * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
 * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
 */
package com.snowplowanalytics.snowplow.collectors.scalastream

import scala.concurrent.duration._
import cats.effect.kernel.Resource
import cats.effect.{ExitCode, IO, IOApp}
import com.snowplowanalytics.snowplow.collectors.scalastream.generated.BuildInfo
import com.snowplowanalytics.snowplow.collectors.scalastream.model._

object StdoutCollector extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    val good = Resource.pure[IO, Sink[IO]](new PrintingSink[IO](System.out))
    val bad  = Resource.pure[IO, Sink[IO]](new PrintingSink[IO](System.err))
    CollectorApp.run[IO](
      good,
      bad,
      CollectorConfig(
        Map.empty,
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
      ),
      BuildInfo.shortName,
      BuildInfo.version
    )
  }
}
