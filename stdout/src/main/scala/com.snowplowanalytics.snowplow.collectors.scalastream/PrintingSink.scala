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
