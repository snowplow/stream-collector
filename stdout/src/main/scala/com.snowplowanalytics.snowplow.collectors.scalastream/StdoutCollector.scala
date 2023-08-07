/*
 * Copyright (c) 2013-2022 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, and
 * you may not use this file except in compliance with the Apache License
 * Version 2.0.  You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Apache License Version 2.0 is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
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
