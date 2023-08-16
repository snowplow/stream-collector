package com.snowplowanalytics.snowplow.collector.core

trait Sink[F[_]] {

  // Maximum number of bytes that a single record can contain.
  // If a record is bigger, a size violation bad row is emitted instead
  val maxBytes: Int

  def isHealthy: F[Boolean]
  def storeRawEvents(events: List[Array[Byte]], key: String): F[Unit]
}
