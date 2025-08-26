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

import java.nio.{ByteBuffer, ByteOrder}
import java.io.OutputStream

/**
  * Responsible for compressing a collection of "records" (arbitrary byte arrays) before we write to
  * a stream
  */
abstract class Compressor(val targetSize: Int) {

  private var _recordCount: Int = 0

  // ---- METHODS IMPLEMENTED BY CONCRETE CLASSES --- //

  protected val compressor: OutputStream

  protected def extraBytesNeededForFooter: Int

  // ---- METHODS OPTIONALLY IMPLEMENTED BY CONCRETE CLASSES --- //

  // Drop a marker. We might need to rewind _this_ state.
  protected def mark(): Unit = ()

  // Rewind to last memoized state when `mark()` got called
  protected def rewindToMark(): Unit = ()

  // To be called if we are satisfied the `write` did not exceed the target byte count
  protected def commit(): Unit = ()

  // ----- FINAL METHODS IMPLEMENTED BY BASE CLASS --- //

  final def recordCount: Int = _recordCount

  final protected val rwos = new RewindableOutputStream(targetSize)

  /**
    * Add another record to this compressed collection of records
    *
    * Adding a record is only successful if the total compressed size remains smaller than the target
    * size.
    *
    * @param record
    *   The uncompressed record to be added
    * @return
    *   A boolean telling us whether the addition was successful. False means the addition exceeded
    *   the target size, and so the record has not been accepted by the compressor.
    */
  final def addRecord(record: Array[Byte], offset: Int, len: Int): Boolean = {
    // Mark the output streams, so we can rewind the mark if we accidentally exceed the target output size
    mark()
    rwos.mark()

    // Write 4 bytes, storing a 32-bit integer telling Enrich the size of the record
    compressor.write(Compressor.sizeAsBytes(len))

    // Now write the record to the compressed stream
    compressor.write(record, offset, len)

    // Flush the compressed stream, so that we can check the new total size of the compressed bytes
    compressor.flush()

    // The `extraBytesNeededForFooter` is needed because calling `.close()` adds an extra bytes to the output, depending on the algorithm
    if (rwos.size() + extraBytesNeededForFooter > targetSize) {
      // We have accidentally exceeded the target size, so rewind the streams to the mark.  This `addRecord` was not successful.
      rwos.rewindToMark()
      rewindToMark()
      // Auto-close since failed addRecord indicates the compressor won't be used further
      close()
      false
    } else {
      // We have not exceeded the target size, so this `addRecord` was successful.
      commit()
      _recordCount += 1
      true
    }
  }

  /**
    * Close the underlying compressor and release associated resources.
    *
    * This should be called when the compressor is no longer needed to prevent memory leaks.
    */
  final def close(): Unit = compressor.close()

  /**
    * The compressed bytes comprising all successfully added records
    */
  final def result: ByteBuffer = {
    close()
    rwos.toByteBuffer()
  }

  // Write the Snowplow headers
  final private def initialize(): Unit = {
    compressor.write(1) // Version number of this compression format
    compressor.write(1) // Version number of the raw stream collector payload format
  }

}

object Compressor {

  trait Factory {
    protected def build(targetSize: Int): Compressor

    final def buildAndInitialize(targetSize: Int): Compressor = {
      val compressor = build(targetSize)
      compressor.initialize()
      compressor
    }
  }

  /**
    * Creates 4 bytes representing a 32-bit integer
    *
    * @param size
    *   The value to serialize as a 32-bit integer
    */
  private def sizeAsBytes(size: Int): Array[Byte] = {
    val bb = ByteBuffer.allocate(4)
    bb.order(ByteOrder.BIG_ENDIAN)
    bb.putInt(size)
    bb.array
  }

}
