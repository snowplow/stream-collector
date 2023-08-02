package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.Applicative

object CollectorTestUtils {

  def noopSink[F[_]: Applicative]: Sink[F] = new Sink[F] {
    val maxBytes: Int                                                   = Int.MaxValue
    def isHealthy: F[Boolean]                                           = Applicative[F].pure(true)
    def storeRawEvents(events: List[Array[Byte]], key: String): F[Unit] = Applicative[F].unit
  }

}
