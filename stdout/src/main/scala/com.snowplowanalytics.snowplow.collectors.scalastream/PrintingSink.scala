/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Snowplow Community License Version 1.0,
 * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
 * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
 */
package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect.Sync
import cats.implicits._

import java.io.PrintStream
import java.util.Base64

class PrintingSink[F[_]: Sync](stream: PrintStream) extends Sink[F] {
  private val encoder: Base64.Encoder = Base64.getEncoder.withoutPadding()

  override val maxBytes: Int         = Int.MaxValue // TODO: configurable?
  override def isHealthy: F[Boolean] = Sync[F].pure(true)

  override def storeRawEvents(events: List[Array[Byte]], key: String): F[Unit] =
    events.traverse_ { event =>
      Sync[F].delay {
        stream.println(encoder.encodeToString(event))
      }
    }
}
