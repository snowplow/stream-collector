package com.snowplowanalytics.snowplow.collector.core
package compression

import cats.effect.testing.specs2.CatsEffect
import org.specs2.mutable.Specification

import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.io.ByteArrayInputStream

class GzipCompressorSpec extends Specification with CatsEffect {
  import CompressorTestUtils._
  import GzipCompressorSpec._

  override def is = s2"""
  GzipCompressor should:
    handle single record                                      $g1
    handle multiple records                                   $g2
    reject record that would exceed target size               $g3
    handle non-zero offset in addRecord                       $g4
    handle empty record                                       $g5
    produce correct format headers                            $g6
    handle boundary case at exact target size                 $g7
    reject record that exceeds target size by one byte        $g8
    handle large records                                      $g9
  """

  def g1 = {
    val compressor = GzipCompressor.factory(6).buildAndInitialize(1000)
    val record     = "test-record-g1".getBytes("UTF-8")

    val result = compressor.addRecord(record, 0, record.length)
    result must beTrue

    val compressed   = compressor.result
    val decompressed = decompressGzip(compressed)

    verifyFormat(decompressed, List(record)) must beTrue
  }

  def g2 = {
    val compressor = GzipCompressor.factory(6).buildAndInitialize(1000)
    val record1    = "record1".getBytes("UTF-8")
    val record2    = "record2".getBytes("UTF-8")
    val record3    = "record3".getBytes("UTF-8")

    compressor.addRecord(record1, 0, record1.length) must beTrue
    compressor.addRecord(record2, 0, record2.length) must beTrue
    compressor.addRecord(record3, 0, record3.length) must beTrue

    val compressed   = compressor.result
    val decompressed = decompressGzip(compressed)

    verifyFormat(decompressed, List(record1, record2, record3)) must beTrue
  }

  def g3 = {
    val compressor  = GzipCompressor.factory(6).buildAndInitialize(30)
    val largeRecord = ("large-record" + "x" * 1000).getBytes("UTF-8")

    val result = compressor.addRecord(largeRecord, 0, largeRecord.length)
    result must beFalse
  }

  def g4 = {
    val compressor     = GzipCompressor.factory(6).buildAndInitialize(1000)
    val fullData       = "prefix_test_record_suffix".getBytes("UTF-8")
    val expectedRecord = "test_record".getBytes("UTF-8")

    val result = compressor.addRecord(fullData, 7, 11) // Extract "test_record"
    result must beTrue

    val compressed   = compressor.result
    val decompressed = decompressGzip(compressed)

    verifyFormat(decompressed, List(expectedRecord)) must beTrue
  }

  def g5 = {
    val compressor  = GzipCompressor.factory(6).buildAndInitialize(1000)
    val emptyRecord = Array.empty[Byte]

    val result = compressor.addRecord(emptyRecord, 0, 0)
    result must beTrue

    val compressed   = compressor.result
    val decompressed = decompressGzip(compressed)

    verifyFormat(decompressed, List(emptyRecord)) must beTrue
  }

  def g6 = {
    val compressor = GzipCompressor.factory(6).buildAndInitialize(1000)
    val record     = "header-test".getBytes("UTF-8")
    compressor.addRecord(record, 0, record.length)
    val compressed   = compressor.result
    val decompressed = decompressGzip(compressed)

    decompressed.length must be_>=(2)
    decompressed(0) must_== 1.toByte // Compression format version
    decompressed(1) must_== 1.toByte // Payload format version
  }

  def g7 = {
    // Test boundary case: use a record that compresses to exactly the target size
    val compressor = GzipCompressor.factory(6).buildAndInitialize(35)

    // The string "abc" (3 bytes) compresses to exactly 35 bytes total:
    // - 2 bytes: compression format headers (1 + 1)
    // - 4 bytes: record length as big-endian int32 (payload size)
    // - ~29 bytes: gzip overhead + compressed "abc" content, where gzip overhead includes:
    //   * 10 bytes: gzip header (magic number, compression method, flags, timestamp, etc.)
    //   * ~15 bytes: deflate compressed data for "abc" (includes huffman coding, LZ77 references)
    //   * 8 bytes: gzip trailer (CRC32 checksum + uncompressed size)
    //   * Small data like "abc" has poor compression ratio due to fixed overhead
    // Total = 2 + 4 + 29 = 35 bytes exactly
    val exactFitRecord = "abc".getBytes("UTF-8")

    val result = compressor.addRecord(exactFitRecord, 0, exactFitRecord.length)
    result must beTrue

    val compressed   = compressor.result
    val decompressed = decompressGzip(compressed)

    verifyFormat(decompressed, List(exactFitRecord)) must beTrue
    // Verify we hit exactly the target size (boundary case)
    compressed.remaining() must_== 35
  }

  def g8 = {
    // Test one-over boundary case: use a record that compresses to one byte over target size
    val compressor = GzipCompressor.factory(6).buildAndInitialize(35)

    // The string "abcd" (4 bytes) compresses to exactly 36 bytes total (one over target):
    val overTargetRecord = "abcd".getBytes("UTF-8")

    val result = compressor.addRecord(overTargetRecord, 0, overTargetRecord.length)
    result must beFalse // Should be rejected as it exceeds target size
  }

  def g9 = {
    val compressor = GzipCompressor.factory(6).buildAndInitialize(50000)

    // Create a genuinely large record (5KB of data)
    val record = ("x" * 5000).getBytes("UTF-8")

    // Verify the record is actually large (5KB)
    record.length must be_>=(5000)

    val result = compressor.addRecord(record, 0, record.length)
    result must beTrue

    val compressed   = compressor.result
    val decompressed = decompressGzip(compressed)

    verifyFormat(decompressed, List(record)) must beTrue

    // Verify compression actually worked (compressed should be much smaller than original)
    compressed.remaining() must be_<(record.length)
  }
}

object GzipCompressorSpec {

  def decompressGzip(compressed: ByteBuffer): Array[Byte] = {
    val input     = new ByteArrayInputStream(compressed.array(), compressed.position(), compressed.remaining())
    val gzipInput = new GZIPInputStream(input)
    val result    = Iterator.continually(gzipInput.read()).takeWhile(_ != -1).map(_.toByte).toArray
    gzipInput.close()
    result
  }

}
