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
        )
      ),
      BuildInfo.shortName,
      BuildInfo.version
    )
  }
}
