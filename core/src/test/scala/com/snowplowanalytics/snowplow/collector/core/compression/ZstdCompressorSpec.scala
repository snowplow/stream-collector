package com.snowplowanalytics.snowplow.collector.core
package compression

import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification

import java.nio.ByteBuffer
import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream

class ZstdCompressorSpec extends Specification with CatsEffect {
  import CompressorTestUtils._
  import ZstdCompressorSpec._

  override def is = s2"""
  ZstdCompressor should:
    handle single record                                      $z1
    handle multiple records                                   $z2
    reject record that would exceed target size               $z3
    handle non-zero offset in addRecord                       $z4
    handle empty record                                       $z5
    produce correct format headers                            $z6
    handle boundary case at exact target size                 $z7
    reject record that exceeds target size by one byte        $z8
    handle large records                                      $z9
  """

  def z1 = {
    val compressor = ZstdCompressor.factory(3).buildAndInitialize(1000)
    val record     = "test-record-z1".getBytes("UTF-8")

    val result = compressor.addRecord(record, 0, record.length)
    result must beTrue

    val compressed   = compressor.result
    val decompressed = decompressZstd(compressed)

    verifyFormat(decompressed, List(record)) must beTrue
  }

  def z2 = {
    val compressor = ZstdCompressor.factory(3).buildAndInitialize(1000)
    val record1    = "record1".getBytes("UTF-8")
    val record2    = "record2".getBytes("UTF-8")
    val record3    = "record3".getBytes("UTF-8")

    compressor.addRecord(record1, 0, record1.length) must beTrue
    compressor.addRecord(record2, 0, record2.length) must beTrue
    compressor.addRecord(record3, 0, record3.length) must beTrue

    val compressed   = compressor.result
    val decompressed = decompressZstd(compressed)

    verifyFormat(decompressed, List(record1, record2, record3)) must beTrue
  }

  def z3 = {
    val compressor  = ZstdCompressor.factory(3).buildAndInitialize(20)
    val largeRecord = ("large-record" + "x" * 1000).getBytes("UTF-8")

    val result = compressor.addRecord(largeRecord, 0, largeRecord.length)
    result must beFalse
  }

  def z4 = {
    val compressor     = ZstdCompressor.factory(3).buildAndInitialize(1000)
    val fullData       = "prefix_test_record_suffix".getBytes("UTF-8")
    val expectedRecord = "test_record".getBytes("UTF-8")

    val result = compressor.addRecord(fullData, 7, 11) // Extract "test_record"
    result must beTrue

    val compressed   = compressor.result
    val decompressed = decompressZstd(compressed)

    verifyFormat(decompressed, List(expectedRecord)) must beTrue
  }

  def z5 = {
    val compressor  = ZstdCompressor.factory(3).buildAndInitialize(1000)
    val emptyRecord = Array.empty[Byte]

    val result = compressor.addRecord(emptyRecord, 0, 0)
    result must beTrue

    val compressed   = compressor.result
    val decompressed = decompressZstd(compressed)

    verifyFormat(decompressed, List(emptyRecord)) must beTrue
  }

  def z6 = {
    val compressor = ZstdCompressor.factory(3).buildAndInitialize(1000)
    val record     = "header-test".getBytes("UTF-8")
    compressor.addRecord(record, 0, record.length)
    val compressed   = compressor.result
    val decompressed = decompressZstd(compressed)

    decompressed.length must be_>=(2)
    decompressed(0) must_== 1.toByte // Compression format version
    decompressed(1) must_== 1.toByte // Payload format version
  }

  def z7 = {
    // Test boundary case: use a record that compresses to exactly the target size
    val compressor = ZstdCompressor.factory(3).buildAndInitialize(21)

    // The string "abc" (3 bytes) compresses to exactly 21 bytes total with Zstd:
    // - 2 bytes: compression format headers (1 + 1)
    // - 4 bytes: record length as big-endian int32 (payload size)
    // - ~15 bytes: zstd overhead + compressed "abc" content, where zstd overhead includes:
    //   * ~4 bytes: zstd frame header (magic number, frame descriptor)
    //   * ~7 bytes: zstd compressed data for "abc" (dictionary, literals, sequences)
    //   * ~4 bytes: optional checksum (if enabled)
    //   * Zstd is much more efficient than gzip for small data
    // Total = 2 + 4 + 15 = 21 bytes exactly
    val exactFitRecord = "abc".getBytes("UTF-8")

    val result = compressor.addRecord(exactFitRecord, 0, exactFitRecord.length)
    result must beTrue

    val compressed   = compressor.result
    val decompressed = decompressZstd(compressed)

    verifyFormat(decompressed, List(exactFitRecord)) must beTrue
    // Verify we hit exactly the target size (boundary case)
    compressed.remaining() must_== 21
  }

  def z8 = {
    // Test one-over boundary case: use a record that compresses to one byte over target size
    val compressor = ZstdCompressor.factory(3).buildAndInitialize(21)

    // The string "abcd" (4 bytes) compresses to exactly 22 bytes total with Zstd (one over target):
    val overTargetRecord = "abcd".getBytes("UTF-8")

    val result = compressor.addRecord(overTargetRecord, 0, overTargetRecord.length)
    result must beFalse // Should be rejected as it exceeds target size
  }

  def z9 = {
    val compressor = ZstdCompressor.factory(3).buildAndInitialize(50000)

    // Create a genuinely large record (5KB of data)
    val record = ("x" * 5000).getBytes("UTF-8")

    // Verify the record is actually large (5KB)
    record.length must be_>=(5000)

    val result = compressor.addRecord(record, 0, record.length)
    result must beTrue

    val compressed   = compressor.result
    val decompressed = decompressZstd(compressed)

    verifyFormat(decompressed, List(record)) must beTrue

    // Verify compression actually worked (compressed should be much smaller than original)
    compressed.remaining() must be_<(record.length)
  }
}

object ZstdCompressorSpec {

  def decompressZstd(compressed: ByteBuffer): Array[Byte] = {
    val input     = new ByteArrayInputStream(compressed.array(), compressed.position(), compressed.remaining())
    val zstdInput = new ZstdInputStream(input)
    val result    = Iterator.continually(zstdInput.read()).takeWhile(_ != -1).map(_.toByte).toArray
    zstdInput.close()
    result
  }

}
