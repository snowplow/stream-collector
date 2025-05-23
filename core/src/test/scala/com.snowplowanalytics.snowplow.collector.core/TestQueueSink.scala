package com.snowplowanalytics.snowplow.collector.core

import cats.effect.IO
import cats.effect.std.QueueSink

import com.snowplowanalytics.snowplow.collector.thrift.CollectorPayload

import java.util.concurrent.atomic.AtomicReference

class TestQueueSink extends QueueSink[IO, CollectorPayload] {

  val result: AtomicReference[List[CollectorPayload]] = new AtomicReference(Nil)

  override def offer(a: CollectorPayload): IO[Unit] =
    IO.delay {
      result.updateAndGet(_ :+ a)
      ()
    }

  override def tryOffer(a: CollectorPayload): IO[Boolean] =
    offer(a).as(true)

}
