package com.snowplowanalytics.snowplow.collector.core

import cats.effect.IO

import scala.collection.mutable.ListBuffer

class TestSink extends Sink[IO] {

  private val buf: ListBuffer[Array[Byte]] = ListBuffer()

  override val maxBytes: Int = Int.MaxValue

  override def isHealthy: IO[Boolean] = IO.pure(true)

  override def storeRawEvents(events: List[Array[Byte]], key: String): IO[Unit] =
    IO.delay(buf ++= events)

  def storedRawEvents: List[Array[Byte]] = buf.toList

}
