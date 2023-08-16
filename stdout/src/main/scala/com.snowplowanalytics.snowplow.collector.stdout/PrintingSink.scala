package com.snowplowanalytics.snowplow.collector.stdout

import java.io.PrintStream
import java.util.Base64

import cats.implicits._

import cats.effect.Sync

import com.snowplowanalytics.snowplow.collector.core.Sink

class PrintingSink[F[_]: Sync](
  maxByteS: Int,
  stream: PrintStream
) extends Sink[F] {
  private val encoder: Base64.Encoder = Base64.getEncoder.withoutPadding()

  override val maxBytes: Int         = maxByteS
  override def isHealthy: F[Boolean] = Sync[F].pure(true)

  override def storeRawEvents(events: List[Array[Byte]], key: String): F[Unit] =
    events.traverse_ { event =>
      Sync[F].delay {
        stream.println(encoder.encodeToString(event))
      }
    }
}
