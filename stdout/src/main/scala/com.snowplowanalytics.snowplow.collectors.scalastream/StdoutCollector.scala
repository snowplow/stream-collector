/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Snowplow Community License Version 1.0,
 * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
 * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
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
