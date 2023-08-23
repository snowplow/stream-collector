package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import cats.Parallel
import cats.effect.kernel.Outcome
import cats.effect.std.Queue
import cats.effect.{Async, Resource}
import cats.implicits._
import com.snowplowanalytics.snowplow.collector.core.{Config, Sink}

object KinesisSink {

  type Event = Array[Byte]

  def create[F[_]: Async: Parallel](
    config: KinesisSinkConfig,
    buffer: Config.Buffer,
    streamName: String
  ): Resource[F, Sink[F]] =
    for {
      eventsBuffer        <- Resource.eval(Queue.unbounded[F, Option[Event]])
      kinesisClient       <- KinesisClient.create(config, streamName)
      kinesisWriteOutcome <- WritingToKinesisTask.run[F](config, buffer, streamName, eventsBuffer, kinesisClient)
      sink                <- Resource.make(createSink(config, eventsBuffer))(stopSink(eventsBuffer, kinesisWriteOutcome))
    } yield sink

  private def createSink[F[_]: Async: Parallel](
    config: KinesisSinkConfig,
    eventsBuffer: Queue[F, Option[Event]]
  ): F[Sink[F]] =
    Async[F].pure {
      new Sink[F] {
        override def isHealthy: F[Boolean] = Async[F].pure(true) //TODO

        override def storeRawEvents(events: List[Event], key: String): F[Unit] =
          events.parTraverse_ { event =>
            eventsBuffer.offer(Some(event))
          }

        override val maxBytes: Int = config.maxBytes
      }
    }

  private def stopSink[F[_]: Async](
    eventsBuffer: Queue[F, Option[Event]],
    kinesisWriteOutcome: F[Outcome[F, Throwable, Unit]]
  ): Sink[F] => F[Unit] = { _ =>
    for {
      _ <- eventsBuffer.offer(None)
      _ <- kinesisWriteOutcome
    } yield ()

  }
}
