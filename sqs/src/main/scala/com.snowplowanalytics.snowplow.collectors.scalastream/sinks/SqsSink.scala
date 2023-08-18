package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import cats.Parallel
import cats.syntax.all._

import cats.effect._
import cats.effect.std.Queue

import software.amazon.awssdk.services.sqs.SqsAsyncClient

import com.snowplowanalytics.snowplow.collector.core.{Config, Sink}
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.WriteToSqsSink.Events

object SqsSink {

  def create[F[_]: Async: Parallel: Concurrent](
    client: SqsAsyncClient,
    queueName: String,
    config: Config.Streams[SqsSinkConfig]
  ): Resource[F, Sink[F]] =
    for {
      eventsBuffer   <- Resource.eval(Queue.unbounded[F, Option[Events]])
      isHealthyState <- Resource.eval(Ref.of[F, Boolean](false)) // TODO: Implement health check
      sqsSinkOutcome <- WriteToSqsSink.run(client, queueName, eventsBuffer, config)
      sink <- Resource.make(createSink(config.sink.maxBytes, eventsBuffer, isHealthyState)) {
        stopSink(eventsBuffer, sqsSinkOutcome)
      }
    } yield sink

  private def createSink[F[_]: Async: Parallel](
    maxBytesVal: Int,
    eventsBuffer: Queue[F, Option[Events]],
    isHealthyState: Ref[F, Boolean]
  ): F[Sink[F]] =
    Async[F].pure {
      new Sink[F] {
        override val maxBytes: Int         = maxBytesVal
        override def isHealthy: F[Boolean] = isHealthyState.get
        override def storeRawEvents(events: List[Array[Byte]], key: String): F[Unit] =
          events.parTraverse_(e => eventsBuffer.offer(Events(e, key).some))
      }
    }

  private def stopSink[F[_]: Async](
    eventsBuffer: Queue[F, Option[Events]],
    sqsWriteOutcome: F[Outcome[F, Throwable, Unit]]
  ): Sink[F] => F[Unit] =
    _ =>
      for {
        _ <- eventsBuffer.offer(None)
        _ <- sqsWriteOutcome
      } yield ()

}
