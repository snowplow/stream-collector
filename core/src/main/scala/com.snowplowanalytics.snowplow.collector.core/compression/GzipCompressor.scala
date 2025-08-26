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
package com.snowplowanalytics.snowplow.collector.core.compression

import java.util.zip.{CRC32, Deflater, GZIPOutputStream}
import java.io.OutputStream

/**
  * Responsible for gzipping a collection of "records" (arbitrary byte arrays) before we write to a
  * stream
  *
  * @param compressionLevel
  *   Compression Level between 1 (best speed) and 9 (best compression). The linux default is 6.
  * @param targetSize
  *   The target size in bytes of the gzipped result. We will add more records to this compressor,
  *   but we must not exceed this target size.
  */
class GzipCompressor(compressionLevel: Int, targetSize: Int) extends Compressor(targetSize) {
  import GzipCompressor._

  override protected val compressor: OutputStream = new SizeCautiousGZIPOutputStream(rwos, compressionLevel)

  override protected def extraBytesNeededForFooter: Int = 10

  override protected def mark(): Unit =
    compressor.asInstanceOf[SizeCautiousGZIPOutputStream].mark()

  override protected def rewindToMark(): Unit =
    compressor.asInstanceOf[SizeCautiousGZIPOutputStream].rewindToMark()

  override protected def commit(): Unit =
    compressor.asInstanceOf[SizeCautiousGZIPOutputStream].commit()
}

object GzipCompressor {

  def factory(compressionLevel: Int): Compressor.Factory = new Compressor.Factory {
    protected def build(targetSize: Int): Compressor =
      new GzipCompressor(compressionLevel, targetSize)
  }

  /**
    * A variation of a GZIPOutputStream which can rewind to an earlier state
    */
  private class SizeCautiousGZIPOutputStream(outputStream: OutputStream, compressionLevel: Int)
      extends GZIPOutputStream(outputStream, true) {

    // Override protected fields `crc` and `def` with our customized classes
    crc   = new CommittableCRC32
    `def` = new RewindableDeflater(compressionLevel)

    // Drop a marker. We might need to rewind _this_ state.
    def mark(): Unit =
      `def`.asInstanceOf[RewindableDeflater].mark()

    // Rewind to last memoized state when `mark()` got called
    def rewindToMark(): Unit =
      `def`.asInstanceOf[RewindableDeflater].rewindToMark()

    // To be called if we are satisfied the `write` did not exceed the target byte count
    def commit(): Unit =
      crc.asInstanceOf[CommittableCRC32].commit()
  }

  /**
    * A variation of a Deflater where we override `getTotalIn` to "lie" about the number of input
    * bytes it deflated.
    *
    * A Deflator is used internally by a GZIPOutputStream. The gzipped output concludes with an
    * integer count of the number of uncompressed bytes. We need a "rewindable" deflater because our
    * GZIPOutputStream needs to rewind the streams in case we exceed the target byte count. When this
    * happens, we make our Deflater "un-count" some of the input bytes it processed.
    */
  private class RewindableDeflater(compressionLevel: Int) extends Deflater(compressionLevel, true) {

    // This local state is not thread-safe. Make the external code responsible for sequencing access to the compressors.

    private var markedValue    = 0 // memoizes the value of `getTotalIn()`
    private var numRewindBytes = 0 // the number of bytes this Deflater should "un-count" after a rewind

    // Drop a marker. We might need to rewind _this_ state.
    def mark(): Unit =
      markedValue = super.getTotalIn()

    // Rewind to last memoized state when `mark()` got called
    def rewindToMark(): Unit =
      numRewindBytes += super.getTotalIn() - markedValue

    override def getTotalIn(): Int =
      super.getTotalIn() - numRewindBytes

  }

  /**
    * A variation of a CRC32 which does not update its internal state until someone calls `.commit()
    *
    * A CRC32 is used internally by a `GZIPOutputStream`. The gzipped output concludes with a CRC32
    * of the uncompressed bytes. We need "committable" CRC32, because our GZIPOutputStream needs to
    * rewind the stream in case we exceed the target byte count.
    */
  private class CommittableCRC32 extends CRC32 {
    private case class PendingUpdate(
      b: Array[Byte],
      off: Int,
      len: Int
    )

    // This local state is not thread-safe. Make the external code responsible for sequencing access to the compressors.
    private var pendingUpdates: Vector[PendingUpdate] = Vector.empty

    override def update(
      b: Array[Byte],
      off: Int,
      len: Int
    ): Unit =
      pendingUpdates = pendingUpdates :+ PendingUpdate(b, off, len)

    def commit(): Unit = {
      pendingUpdates.foreach {
        case PendingUpdate(b, off, len) =>
          super.update(b, off, len)
      }
      pendingUpdates = Vector.empty
    }

  }

}
