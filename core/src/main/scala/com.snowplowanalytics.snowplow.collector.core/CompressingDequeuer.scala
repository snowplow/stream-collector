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

import fs2.{Chunk, Pipe, Pull}
import cats.Foldable
import cats.effect.{Async, Sync}
import cats.implicits._
import org.apache.thrift.TBaseHelper
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.TMemoryTransport

import com.snowplowanalytics.snowplow.collector.thrift.CollectorPayload
import com.snowplowanalytics.snowplow.badrows.{BadRow, Failure, Payload, Processor}
import com.snowplowanalytics.snowplow.collector.core.compression.{Compressor, GzipCompressor, ZstdCompressor}

import scala.concurrent.duration.{Duration, DurationLong}
import java.time.Instant

object CompressingDequeuer {

  /**
    * Represents a batch of events that are being accumulated before emission to the sink.
    *
    * @param serialized Compressed records ready to get emitted
    * @param inProgress Current compressor used to compress next events
    * @param outputCount Number of events ready to get emitted
    * @param serializedByteCount Total bytes from already-compressed batches (excludes inProgress)
    */
  private case class PendingOutput(
    serialized: List[Array[Byte]],
    inProgress: Compressor,
    outputCount: Int,
    serializedByteCount: Long
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
    * @param factory Factory for creating new compressors
    * @param timedPull FS2 Pull with timeout capabilities for batching
    * @param sink Target sink for emitting compressed batches
    */
  private case class ProcessingContext[F[_]](
    config: Config.Buffer,
    appInfo: AppInfo,
    factory: Compressor.Factory,
    timedPull: Pull.Timed[F, _],
    sink: Sink[F]
  )

  def batchAndSinkGood[F[_]: Async](
    appInfo: AppInfo,
    compressionConfig: Config.Compression,
    bufferConfig: Config.Buffer,
    sink: Sink[F]
  ): Pipe[F, CollectorPayload, BadRow.SizeViolation] = {

    val factory = compressionConfig.`type` match {
      case Config.Compression.GZIP =>
        GzipCompressor.factory(compressionConfig.gzipCompressionLevel)
      case Config.Compression.ZSTD =>
        ZstdCompressor.factory(compressionConfig.zstdCompressionLevel)
    }

    def go(
      timedPull: Pull.Timed[F, CollectorPayload],
      maybePending: Option[PendingOutput]
    ): Pull[F, BadRow.SizeViolation, Unit] =
      timedPull.uncons.flatMap {
        case None =>
          // Upstream finished cleanly. Emit whatever is pending and we're done.
          Pull.eval(emitToSink(sink, maybePending))
        case Some((Left(_), next)) =>
          // Timer timed-out. Emit whatever is pending
          Pull.eval(emitToSink(sink, maybePending)) >> go(next, None)
        case Some((Right(chunk), next)) =>
          Foldable[Chunk]
            .foldM(chunk, maybePending) {
              case (maybePending, cp) =>
                val transport = new TMemoryTransport(Array())
                val protocol  = new TBinaryProtocol(transport)
                cp.write(protocol)
                val context = ProcessingContext(bufferConfig, appInfo, factory, next, sink)
                val payload = PayloadData(cp, transport.getOutput.get, 0, transport.getOutput.len)
                handleCollectorPayload(context, maybePending, payload)
            }
            .flatMap { result =>
              val cancelTimeout = if (result.isEmpty) next.timeout(Duration.Zero) else Pull.done
              cancelTimeout >> go(next, result)
            }
      }

    _.pull.timed { timedPull =>
      go(timedPull, None)
    }.stream
  }

  /**
    * Processes a single CollectorPayload by attempting to add it to the current compressor.
    *
    * This is the core logic that handles:
    * - Adding payloads to an existing compressor
    * - Emitting full batches when size/count limits are reached
    * - Creating a new compressor when the previous one reached the limit
    *
    * @param context Processing dependencies (config, sink, factory, etc.)
    * @param maybePending Current batch state, or None to open a new pending state
    * @param payload The CollectorPayload to process
    * @return An optional pending state
    */
  private def handleCollectorPayload[F[_]: Sync](
    ctx: ProcessingContext[F],
    maybePending: Option[PendingOutput],
    payload: PayloadData
  ): Pull[F, BadRow.SizeViolation, Option[PendingOutput]] =
    for {
      pending <- maybePending match {
        case Some(p) => Pull.pure(p)
        case None    => openNewPendingOutput(ctx.config, ctx.factory, ctx.timedPull, ctx.sink)
      }
      result <- pending.inProgress.addRecord(payload.cpBytes, payload.cpBytesOffset, payload.cpBytesLength) match {
        case true =>
          // payload was successfully added to the compressor
          Pull.pure(Some(pending))
        case false =>
          // compressed payload was too big for this compressor
          if (pending.inProgress.recordCount === 0) {
            // Single record failed to compress to targetBytes
            handleSingleRecordFailure(ctx.appInfo, payload, pending, ctx.factory, ctx.sink)
          } else {
            val compressedBytes = TBaseHelper.byteBufferToByteArray(pending.inProgress.result)
            // Get current sink target bytes for accurate estimation of new batch size
            Pull.eval(ctx.sink.targetBytes).flatMap { currentTargetBytes =>
              if (pending.serializedByteCount + compressedBytes.size + pending.inProgress.targetSize > ctx
                    .config
                    .byteLimit || pending.outputCount + 1 > ctx.config.recordLimit) {
                for {
                  _      <- Pull.eval(emitBytesToSink(ctx.sink, compressedBytes :: pending.serialized))
                  result <- handleCollectorPayload(ctx, None, payload)
                } yield result
              } else {
                val compressor = ctx.factory.buildAndInitialize(currentTargetBytes)
                val nextPending = PendingOutput(
                  compressedBytes :: pending.serialized,
                  compressor,
                  pending.outputCount         + 1,
                  pending.serializedByteCount + compressedBytes.size
                )
                handleCollectorPayload(ctx, Some(nextPending), payload)
              }
            }
          }
      }
    } yield result

  private def emitToSink[F[_]: Sync](
    sink: Sink[F],
    maybePending: Option[PendingOutput]
  ): F[Unit] =
    maybePending match {
      case None => Sync[F].unit
      case Some(pending) =>
        if (pending.inProgress.recordCount > 0) {
          val bb    = pending.inProgress.result
          val bytes = TBaseHelper.byteBufferToByteArray(bb)
          emitBytesToSink(sink, bytes :: pending.serialized)
        } else {
          emitBytesToSink(sink, pending.serialized)
        }
    }

  private def emitBytesToSink[F[_]: Sync](sink: Sink[F], messages: List[Array[Byte]]): F[Unit] =
    if (messages.nonEmpty)
      sink.storeRawEvents(messages)
    else
      Sync[F].unit

  private def openNewPendingOutput[F[_]](
    config: Config.Buffer,
    factory: Compressor.Factory,
    timedPull: Pull.Timed[F, _],
    sink: Sink[F]
  ): Pull[F, Nothing, PendingOutput] =
    for {
      _           <- timedPull.timeout(config.timeLimit.millis)
      targetBytes <- Pull.eval(sink.targetBytes)
    } yield {
      val compressor = factory.buildAndInitialize(targetBytes)
      // Start with outputCount=1 to represent the message corresponding to this compressor
      PendingOutput(Nil, compressor, 1, 0L)
    }

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

  /**
    * Creates a new compressor using the current target size from the sink.
    *
    * This ensures new compressors use up-to-date target sizes, which is important
    * when sink health changes (e.g., Kinesis switching between healthy/rate-limited).
    *
    * @param factory Factory for creating compressors
    * @param sink Sink to get current targetBytes from
    * @return A new initialized compressor
    */
  private def createCompressor[F[_]](
    factory: Compressor.Factory,
    sink: Sink[F]
  ): Pull[F, Nothing, Compressor] =
    Pull.eval(sink.targetBytes).map(factory.buildAndInitialize)

  /**
    * Creates a size violation bad row and resets the compressor for the next payload.
    *
    * This is the common pattern used when a payload exceeds the maximum allowed size.
    * It generates the appropriate bad row and prepares for processing the next payload.
    *
    * @param appInfo Application info for the bad row
    * @param cp The oversized CollectorPayload
    * @param cpBytesLength Size of the oversized payload
    * @param pending Current batch state to reset
    * @param factory Factory for creating the replacement compressor
    * @param sink Sink for getting current target size
    * @return An optional pending state
    */
  private def createSizeViolationAndResetCompressor[F[_]](
    appInfo: AppInfo,
    cp: CollectorPayload,
    cpBytesLength: Int,
    pending: PendingOutput,
    factory: Compressor.Factory,
    sink: Sink[F]
  ): Pull[F, BadRow.SizeViolation, Option[PendingOutput]] = {
    val br = oversizedPayload(appInfo, cp, cpBytesLength, sink.maxBytes)
    for {
      compressor <- createCompressor(factory, sink)
      result     <- Pull.output1(br).as(Some(pending.copy(inProgress = compressor)))
    } yield result
  }

  /**
    * Handles the edge case where a single record fails to compress to targetBytes.
    *
    * When a payload can't compress to targetBytes
    * (e.g., 192KB during Kinesis rate limiting) but targetBytes < maxBytes, we retry
    * compression with maxBytes (e.g., 1MB) to maintain the guarantee that all
    * payloads under 1MB are accepted.
    *
    * @param appInfo Application info for potential bad rows
    * @param payload The payload that failed initial compression
    * @param pending Current batch state
    * @param factory Factory for creating compressors
    * @param sink Sink providing maxBytes and targetBytes
    * @return An optional pending state
    */
  private def handleSingleRecordFailure[F[_]](
    appInfo: AppInfo,
    payload: PayloadData,
    pending: PendingOutput,
    factory: Compressor.Factory,
    sink: Sink[F]
  ): Pull[F, BadRow.SizeViolation, Option[PendingOutput]] =
    if (pending.inProgress.targetSize < sink.maxBytes) {
      // Try again with maxBytes if we were using a smaller target
      val maxBytesCompressor = factory.buildAndInitialize(sink.maxBytes)
      if (maxBytesCompressor.addRecord(payload.cpBytes, payload.cpBytesOffset, payload.cpBytesLength)) {
        Pull.pure(Some(pending.copy(inProgress = maxBytesCompressor)))
      } else {
        // Payload too large even for maxBytes - compressor auto-closed by addRecord failure
        createSizeViolationAndResetCompressor(appInfo, payload.cp, payload.cpBytesLength, pending, factory, sink)
      }
    } else {
      createSizeViolationAndResetCompressor(appInfo, payload.cp, payload.cpBytesLength, pending, factory, sink)
    }
}
