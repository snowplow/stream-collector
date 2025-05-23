package com.snowplowanalytics.snowplow.collector.core

import cats.effect.{IO, Ref}

class TestSink(val receivedBatchSizes: Ref[IO, List[Int]]) extends Sink[IO] {

  override val maxBytes: Int = 10000

  override def isHealthy: IO[Boolean] = IO.pure(true)

  override def storeRawEvents(events: List[Array[Byte]]): IO[Unit] =
    receivedBatchSizes.update(_ :+ events.length)

}

object TestSink {

  def build: IO[TestSink] =
    Ref[IO].of(List.empty[Int]).map(new TestSink(_))

}
