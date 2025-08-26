/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This software is made available by Snowplow Analytics, Ltd.,
  * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
  * located at https://docs.snowplow.io/limited-use-license-1.1
  * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
  * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
  */
package com.snowplowanalytics.snowplow.collector.core

import fs2.{Chunk, Pipe, Pull, Stream}
import cats.{Applicative, Foldable}
import cats.effect.std.QueueSource
import cats.effect.Async
import cats.implicits._
import cats.effect.implicits._

import com.snowplowanalytics.snowplow.collector.thrift.CollectorPayload
import com.snowplowanalytics.snowplow.badrows.BadRow

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.{Duration, DurationLong}

case class Sinks[F[_]](good: Sink[F], bad: Sink[F])

object Sinks {

  /** Dequeues `CollectorPayload`s from a Queue, serializes them, and hands them over to the good and bad `Sink`s
    *
    *  This `dequeue` is responsible for batching up payloads, according to a time limit and size limits.
    *
    *  @config The app config
    *  @appInfo Details of this variant of collector
    *  @queue The queue from which to pull `CollectorPayload`s
    *  @sinks The good and bad `Sink`s, responsible for sinking serialized payloads to the output streams
    */
  def dequeue[F[_]: Async](
    config: Config[Any],
    appInfo: AppInfo,
    queue: QueueSource[F, CollectorPayload],
    sinks: Sinks[F]
  ): Stream[F, Nothing] =
    Stream
      .fromQueueUnterminated(queue)
      .through {
        if (config.compression.enabled)
          CompressingDequeuer.batchAndSinkGood(appInfo, config.compression, config.streams.good.buffer, sinks.good)
        else
          batchAndSinkGood(config.streams.good.buffer, config.networking, new SplitBatch(appInfo), sinks.good)
      }
      .through(batchAndSinkBad(config.streams.bad.buffer, sinks.bad))

  /** A fs2 `Pipe` that batches up `CollectorPayload`s and sends serialized batches to the good `Sink`
    *
    *  The output type of this `Pipe` is `BadRow.SizeViolation`. These are the bad rows that this `Pipe` could not write into the good `Sink`.
    */
  private def batchAndSinkGood[F[_]: Async](
    config: Config.Buffer,
    networking: Config.Networking,
    splitter: SplitBatch,
    sink: Sink[F]
  ): Pipe[F, CollectorPayload, BadRow.SizeViolation] = {
    def go(timedPull: Pull.Timed[F, CollectorPayload], pending: PendingOutput): Pull[F, BadRow.SizeViolation, Unit] =
      timedPull.uncons.flatMap {
        case None =>
          // Upstream finished cleanly. Emit whatever is pending and we're done.
          Pull.eval(writeToSink(sink, pending.serialized))
        case Some((Left(_), next)) =>
          // Timer timed-out. Emit whatever is pending
          Pull.eval(writeToSink(sink, pending.serialized)) >> go(next, PendingOutput(Nil, 0, 0L))
        case Some((Right(chunk), next)) =>
          // Upstream emitted something to us. We might already have pending payloads.
          val setTimeout = if (pending.serialized.isEmpty) next.timeout(config.timeLimit.millis) else Pull.done
          setTimeout >>
            Foldable[Chunk]
              .foldM(chunk, pending) {
                case (acc, cp) =>
                  val splitResult =
                    splitter.splitAndSerializePayload(cp, sink.maxBytes, networking.maxPayloadSize)
                  val emitBad = if (splitResult.bad.isEmpty) Pull.done else Pull.output(Chunk.from(splitResult.bad))
                  emitBad >>
                    Foldable[List].foldM(splitResult.good, acc) {
                      case (acc, bytes) =>
                        if (acc.totalSizeBytes + bytes.size > config.byteLimit || acc.numItems + 1 > config.recordLimit) {
                          Pull
                            .eval(writeToSink(sink, acc.serialized))
                            .as(PendingOutput(List(bytes), 1, bytes.size.toLong))
                        } else {
                          Pull.pure(
                            PendingOutput(
                              bytes :: acc.serialized,
                              acc.numItems      + 1,
                              bytes.size.toLong + acc.totalSizeBytes
                            )
                          )
                        }
                    }
              }
              .flatMap { result =>
                val cancelTimeout = if (result.serialized.isEmpty) next.timeout(Duration.Zero) else Pull.done
                cancelTimeout >> go(next, result)
              }
      }

    _.pull.timed { timedPull =>
      go(timedPull, PendingOutput(Nil, 0, 0L))
    }.stream
  }

  /** Intermediate state of our fs2 `Pipe`s for batching up events according to time limit and size limits
    *
    *  @param serialized Serialized payloads which are ready to hand over to the `Sink`.  If this is non-empty, it is because we have not yet reached the time limit or size limit.
    *  @param numItems Length of the `serialized` list.
    *  @param totalSizeBytes Total size of all `Array[Byte]` in the `serialized` list
    */
  private case class PendingOutput(
    serialized: List[Array[Byte]],
    numItems: Int,
    totalSizeBytes: Long
  )

  private def writeToSink[F[_]: Async](sink: Sink[F], messages: List[Array[Byte]]): F[Unit] =
    if (messages.nonEmpty)
      sink.storeRawEvents(messages).start.void
    else
      Applicative[F].unit

  /** A fs2 `Pipe` that batches up `SizeViolations`s and sends serialized batches to the bad `Sink` */
  private def batchAndSinkBad[F[_]: Async](
    config: Config.Buffer,
    sink: Sink[F]
  ): Pipe[F, BadRow.SizeViolation, Nothing] = {
    def go(timedPull: Pull.Timed[F, BadRow.SizeViolation], pending: PendingOutput): Pull[F, Nothing, Unit] =
      timedPull.uncons.flatMap {
        case None =>
          // Upstream finished cleanly. Emit whatever is pending and we're done.
          Pull.eval(writeToSink(sink, pending.serialized))
        case Some((Left(_), next)) =>
          // Timer timed-out. Emit whatever is pending
          Pull.eval(writeToSink(sink, pending.serialized)) >> go(next, PendingOutput(Nil, 0, 0L))
        case Some((Right(chunk), next)) =>
          // Upstream emitted something to us. We might already have pending bad rows.
          val setTimeout = if (pending.serialized.isEmpty) next.timeout(config.timeLimit.millis) else Pull.done
          setTimeout >>
            Foldable[Chunk]
              .foldM(chunk, pending) {
                case (acc, badRow) =>
                  val bytes = badRow.compact.getBytes(StandardCharsets.UTF_8)
                  if (acc.totalSizeBytes + bytes.size > config.byteLimit || acc.numItems + 1 > config.recordLimit) {
                    Pull.eval(writeToSink(sink, acc.serialized)).as(PendingOutput(List(bytes), 1, bytes.size.toLong))
                  } else {
                    Pull.pure(
                      PendingOutput(bytes :: acc.serialized, acc.numItems + 1, bytes.size.toLong + acc.totalSizeBytes)
                    )
                  }
              }
              .flatMap { result =>
                val cancelTimeout = if (result.serialized.isEmpty) next.timeout(Duration.Zero) else Pull.done
                cancelTimeout >> go(next, result)
              }
      }

    _.pull.timed { timedPull =>
      go(timedPull, PendingOutput(Nil, 0, 0L))
    }.stream
  }
}
