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

import cats.effect.{ExitCode, IO, IOApp, Sync}
import cats.effect.kernel.Resource
import cats.implicits._

import java.util.Base64
import java.io.PrintStream

object StdoutCollector extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    val good = Resource.pure[IO, Sink[IO]](printingSink(System.out))
    val bad  = Resource.pure[IO, Sink[IO]](printingSink(System.err))
    CollectorApp.run[IO](good, bad)
  }

  private def printingSink[F[_]: Sync](stream: PrintStream): Sink[F] = new Sink[F] {
    val maxBytes              = Int.MaxValue // TODO: configurable?
    def isHealthy: F[Boolean] = Sync[F].pure(true)

    val encoder = Base64.getEncoder().withoutPadding()

    def storeRawEvents(events: List[Array[Byte]], key: String): F[Unit] =
      events.traverse_ { e =>
        Sync[F].delay {
          stream.println(encoder.encodeToString(e))
        }
      }
  }
}
