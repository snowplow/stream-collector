/* Copyright (c) 2013-present Snowplow Analytics Ltd.
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
import cats.Foldable
import cats.effect.{Async, Sync}
import cats.effect.std.Supervisor
import cats.implicits._
import org.apache.thrift.TBaseHelper
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.TMemoryTransport

import com.snowplowanalytics.snowplow.collector.thrift.CollectorPayload
import com.snowplowanalytics.snowplow.badrows.{BadRow, Failure, Payload, Processor}
import com.snowplowanalytics.snowplow.streams.compression.{Compressor, CompressorFactory}

import scala.concurrent.duration.{Duration, DurationLong}
import java.time.Instant

object CompressingDequeuer {

  private val CollectorPayloadFormatVersion = 1

  /**
    * Represents a batch of events that are being accumulated before emission to the sink.
    *
    * The in-progress batch lives in the shared, long-lived `Compressor`'s mutable state; this
    * case class holds only the immutable accumulated state around it.
    *
    * @param serialized Compressed records ready to get emitted
    * @param outputCount Number of events ready to get emitted
    * @param serializedByteCount Total bytes from already-compressed batches (excludes the in-progress batch)
    * @param currentTargetSize The target size the shared compressor was last reset to for the in-progress batch
    */
  private case class PendingOutput(
    serialized: List[Array[Byte]],
    outputCount: Int,
    serializedByteCount: Long,
    currentTargetSize: Int
  )

  /**
    * Contains a CollectorPayload and its serialized byte representation.
    * This avoids passing the same data in multiple separate parameters.
    *
    * @param cp The original CollectorPayload object
    * @param cpBytes The serialized Thrift bytes of the CollectorPayload
    * @param cpBytesOffset Starting offset within cpBytes (usually 0)
    * @param cpBytesLength Number of bytes for the serialized payload in cpBytes
    */
  private case class PayloadData(
    cp: CollectorPayload,
    cpBytes: Array[Byte],
    cpBytesOffset: Int,
    cpBytesLength: Int
  )

  /**
    * Groups together all the processing dependencies needed for compression and batching.
    * This eliminates the need to pass many individual parameters to helper functions.
    *
    * @param config Buffer configuration (size limits, timeouts, etc.)
    * @param appInfo Application metadata for error reporting
    * @param compressor The single long-lived compressor, reset between batches
    * @param timedPull FS2 Pull with timeout capabilities for batching
    * @param sink Target sink for emitting compressed batches
    * @param supervisor Supervisor manages lifecycle of any fibers we start
    */
  private case class ProcessingContext[F[_]](
    config: Config.Buffer,
    appInfo: AppInfo,
    compressor: Compressor,
    timedPull: Pull.Timed[F, _],
    sink: Sink[F],
    supervisor: Supervisor[F]
  )

  def batchAndSinkGood[F[_]: Async](
    appInfo: AppInfo,
    compressionConfig: Config.Compression,
    bufferConfig: Config.Buffer,
    sink: Sink[F],
    supervisor: Supervisor[F]
  ): Pipe[F, CollectorPayload, BadRow.SizeViolation] = {

    val factory = compressionConfig.`type` match {
      case Config.Compression.GZIP =>
        CompressorFactory.gzip(compressionConfig.gzipCompressionLevel)
      case Config.Compression.ZSTD =>
        CompressorFactory.zstd(compressionConfig.zstdCompressionLevel)
    }

    def go(
      timedPull: Pull.Timed[F, CollectorPayload],
      maybePending: Option[PendingOutput],
      compressor: Compressor
    ): Pull[F, BadRow.SizeViolation, Unit] =
      timedPull.uncons.flatMap {
        case None =>
          // Upstream finished cleanly. Emit whatever is pending and we're done.
          Pull.eval(emitAnythingPendingToSink(supervisor, sink, maybePending, compressor))
        case Some((Left(_), next)) =>
          // Timer timed-out. Emit whatever is pending
          Pull.eval(emitAnythingPendingToSink(supervisor, sink, maybePending, compressor)) >> go(next, None, compressor)
        case Some((Right(chunk), next)) =>
          Foldable[Chunk]
            .foldM(chunk, maybePending) {
              case (maybePending, cp) =>
                val context = ProcessingContext(bufferConfig, appInfo, compressor, next, sink, supervisor)
                for {
                  payload <- Pull.eval(Sync[F].delay(serializeCollectorPayload(cp)))
                  pending <- getOrCreatePendingOutput(context, maybePending)
                  result  <- handleCollectorPayload(context, pending, payload)
                } yield result
            }
            .flatMap { result =>
              val cancelTimeout = if (result.isEmpty) next.timeout(Duration.Zero) else Pull.done
              cancelTimeout >> go(next, result, compressor)
            }
      }

    in =>
      Stream.resource(factory.resource[F]).flatMap { compressor =>
        in.pull
          .timed { timedPull =>
            go(timedPull, None, compressor)
          }
          .stream
      }
  }

  /**
    * Gets the existing pending output or creates a new one if none exists.
    *
    * Ensure we use the latest target size for the compressor. And ensures we
    * reset the timed pull, to limit the delay until this pending output gets
    * emitted to the sink.
    *
    * @param ctx Processing dependencies (config, sink, compressor, etc.)
    * @param maybePending Current batch state, or None to create a new pending state
    * @return A pending output ready for processing events
    */
  private def getOrCreatePendingOutput[F[_]: Sync](
    ctx: ProcessingContext[F],
    maybePending: Option[PendingOutput]
  ): Pull[F, BadRow.SizeViolation, PendingOutput] =
    maybePending match {
      case Some(pending) =>
        Pull.pure(pending)
      case None =>
        for {
          targetSize    <- resetWithLatestTargetSize(ctx.compressor, ctx.sink)
          pendingOutput <- openNewPendingOutput(ctx.config, ctx.timedPull, targetSize)
        } yield pendingOutput
    }

  /**
    * Processes a single CollectorPayload by attempting to add it to the shared compressor.
    *
    * This is the core logic that handles:
    * - Adding payloads to the in-progress batch
    * - Emitting full batches when size/count limits are reached
    * - Resetting the compressor for a new batch when the previous one reached the limit
    *
    * @param ctx Processing dependencies (config, sink, compressor, etc.)
    * @param pending Current batch state (guaranteed to exist)
    * @param payload The CollectorPayload to process
    * @return An optional pending state (None if batch was emitted)
    */
  private def handleCollectorPayload[F[_]: Async](
    ctx: ProcessingContext[F],
    pending: PendingOutput,
    payload: PayloadData
  ): Pull[F, BadRow.SizeViolation, Option[PendingOutput]] =
    if (ctx.compressor.addRecord(payload.cpBytes, payload.cpBytesOffset, payload.cpBytesLength)) {
      // payload was successfully added to the compressor
      Pull.pure(Some(pending))
    } else if (ctx.compressor.recordCount === 0) {
      // Single record failed to compress to targetBytes
      handleSingleRecordFailure(ctx, payload, pending)
    } else {
      // compressed payload was too big for this compressor
      val compressedBytes = TBaseHelper.byteBufferToByteArray(ctx.compressor.result)
      // Reset the compressor with the current sink target bytes for the next batch
      resetWithLatestTargetSize(ctx.compressor, ctx.sink).flatMap { nextTargetSize =>
        if (pending.serializedByteCount + compressedBytes.size + nextTargetSize > ctx
              .config
              .byteLimit || pending.outputCount + 1 > ctx.config.recordLimit) {
          for {
            _           <- Pull.eval(emitBytesToSink(ctx.supervisor, ctx.sink, compressedBytes :: pending.serialized))
            nextPending <- openNewPendingOutput(ctx.config, ctx.timedPull, nextTargetSize)
            result      <- handleCollectorPayload(ctx, nextPending, payload)
          } yield result
        } else {
          val nextPending = PendingOutput(
            compressedBytes :: pending.serialized,
            pending.outputCount         + 1,
            pending.serializedByteCount + compressedBytes.size,
            nextTargetSize
          )
          handleCollectorPayload(ctx, nextPending, payload)
        }
      }
    }

  private def emitAnythingPendingToSink[F[_]: Async](
    supervisor: Supervisor[F],
    sink: Sink[F],
    maybePending: Option[PendingOutput],
    compressor: Compressor
  ): F[Unit] =
    maybePending match {
      case None => Sync[F].unit
      case Some(pending) =>
        if (compressor.recordCount > 0) {
          val bb    = compressor.result
          val bytes = TBaseHelper.byteBufferToByteArray(bb)
          emitBytesToSink(supervisor, sink, bytes :: pending.serialized)
        } else {
          emitBytesToSink(supervisor, sink, pending.serialized)
        }
    }

  private def emitBytesToSink[F[_]: Async](
    supervisor: Supervisor[F],
    sink: Sink[F],
    messages: List[Array[Byte]]
  ): F[Unit] =
    if (messages.nonEmpty)
      supervisor.supervise(sink.storeRawEvents(messages)).void
    else
      Sync[F].unit

  private def openNewPendingOutput[F[_]](
    config: Config.Buffer,
    timedPull: Pull.Timed[F, _],
    targetSize: Int
  ): Pull[F, Nothing, PendingOutput] =
    for {
      _ <- timedPull.timeout(config.timeLimit.millis)
    } yield PendingOutput(Nil, 1, 0L, targetSize)

  private def oversizedPayload(
    appInfo: AppInfo,
    event: CollectorPayload,
    size: Int,
    maxSize: Int
  ): BadRow.SizeViolation =
    BadRow.SizeViolation(
      Processor(appInfo.name, appInfo.version),
      Failure.SizeViolation(
        Instant.now(),
        maxSize,
        size,
        s"oversized collector payload: Uncompressed size $size exceeded max allowed size $maxSize after compression"
      ),
      Payload.RawPayload(event.toString().take(maxSize / 10))
    )

  private def serializeCollectorPayload(cp: CollectorPayload): PayloadData = {
    val transport = new TMemoryTransport(Array())
    val protocol  = new TBinaryProtocol(transport)
    cp.write(protocol)
    PayloadData(cp, transport.getOutput.get, 0, transport.getOutput.len)
  }

  /**
    * Resets the shared compressor to begin a new batch, using the current target size from the sink.
    *
    * This ensures each new batch uses an up-to-date target size, which is important when sink
    * health changes (e.g., Kinesis switching between healthy/rate-limited). The caller must have
    * already extracted (via `result`) any bytes it needs from the previous batch, because `reset`
    * discards the in-progress frame.
    *
    * @param compressor The shared long-lived compressor
    * @param sink Sink to get current targetBytes from
    * @return The target size the compressor was reset to
    */
  private def resetWithLatestTargetSize[F[_]: Sync](
    compressor: Compressor,
    sink: Sink[F]
  ): Pull[F, Nothing, Int] =
    Pull.eval {
      sink.targetBytes.flatMap { targetSize =>
        Sync[F].delay(compressor.reset(CollectorPayloadFormatVersion, targetSize)).as(targetSize)
      }
    }

  /**
    * Creates a size violation bad row and resets the compressor for the next payload.
    *
    * This is the common pattern used when a payload exceeds the maximum allowed size.
    * It generates the appropriate bad row and prepares for processing the next payload.
    *
    * @param ctx Processing dependencies (config, sink, compressor, etc.)
    * @param cp The oversized CollectorPayload
    * @param cpBytesLength Size of the oversized payload
    * @param pending Current batch state to reset
    * @return An optional pending state
    */
  private def createSizeViolationAndResetCompressor[F[_]: Sync](
    ctx: ProcessingContext[F],
    cp: CollectorPayload,
    cpBytesLength: Int,
    pending: PendingOutput
  ): Pull[F, BadRow.SizeViolation, Option[PendingOutput]] = {
    val br = oversizedPayload(ctx.appInfo, cp, cpBytesLength, ctx.sink.maxBytes)
    for {
      targetSize <- resetWithLatestTargetSize(ctx.compressor, ctx.sink)
      _          <- Pull.output1(br)
    } yield Some(pending.copy(currentTargetSize = targetSize))
  }

  /**
    * Handles the edge case where a single record fails to compress to targetBytes.
    *
    * When a payload can't compress to targetBytes
    * (e.g., 192KB during Kinesis rate limiting) but targetBytes < maxBytes, we retry
    * compression with maxBytes (e.g., 1MB) to maintain the guarantee that all
    * payloads under 1MB are accepted. This is safe because a single-record failure only
    * happens when the in-progress batch has no committed records, so resetting the shared
    * compressor to a larger target loses nothing.
    *
    * @param ctx Processing dependencies (config, sink, compressor, etc.)
    * @param payload The payload that failed initial compression
    * @param pending Current batch state
    * @return An optional pending state
    */
  private def handleSingleRecordFailure[F[_]: Sync](
    ctx: ProcessingContext[F],
    payload: PayloadData,
    pending: PendingOutput
  ): Pull[F, BadRow.SizeViolation, Option[PendingOutput]] =
    if (pending.currentTargetSize < ctx.sink.maxBytes) {
      // Try again with maxBytes if we were using a smaller target
      Pull.eval(Sync[F].delay(ctx.compressor.reset(CollectorPayloadFormatVersion, ctx.sink.maxBytes))).flatMap { _ =>
        if (ctx.compressor.addRecord(payload.cpBytes, payload.cpBytesOffset, payload.cpBytesLength)) {
          Pull.pure(Some(pending.copy(currentTargetSize = ctx.sink.maxBytes)))
        } else {
          // Payload too large even for maxBytes
          createSizeViolationAndResetCompressor(ctx, payload.cp, payload.cpBytesLength, pending)
        }
      }
    } else {
      createSizeViolationAndResetCompressor(ctx, payload.cp, payload.cpBytesLength, pending)
    }
}
