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
