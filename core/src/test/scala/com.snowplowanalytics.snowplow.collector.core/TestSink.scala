package com.snowplowanalytics.snowplow.collector.core

import cats.effect.{IO, Ref}
import java.util.zip.GZIPInputStream
import java.io.ByteArrayInputStream
import com.github.luben.zstd.Zstd

class TestSink(
  val receivedBatchSizes: Ref[IO, List[Int]],
  val receivedRawEvents: Ref[IO, List[List[Array[Byte]]]],
  val maxBytes: Int         = 10000,
  val targetBytesValue: Int = 10000
) extends Sink[IO] {

  override def isHealthy: IO[Boolean] = IO.pure(true)

  override def targetBytes: IO[Int] = IO.pure(targetBytesValue)

  override def storeRawEvents(events: List[Array[Byte]]): IO[Unit] =
    for {
      _ <- receivedBatchSizes.update(_ :+ events.length)
      _ <- receivedRawEvents.update(_ :+ events)
    } yield ()

  /** Decompress and count events in all received compressed batches */
  def getDecompressedEventCount(compressionType: Config.Compression.Type): IO[Int] =
    receivedRawEvents.get.map { batches =>
      batches.map { batch =>
        batch.map(decompressAndCountEvents(_, compressionType)).sum
      }.sum
    }

  private def decompressAndCountEvents(compressedBytes: Array[Byte], compressionType: Config.Compression.Type): Int = {
    val decompressed = decompress(compressedBytes, compressionType)
    countEventsInDecompressedData(decompressed)
  }

  private def decompress(compressedBytes: Array[Byte], compressionType: Config.Compression.Type): Array[Byte] =
    compressionType match {
      case Config.Compression.GZIP =>
        val inputStream = new GZIPInputStream(new ByteArrayInputStream(compressedBytes))
        try {
          inputStream.readAllBytes()
        } finally {
          inputStream.close()
        }
      case Config.Compression.ZSTD =>
        Zstd.decompress(compressedBytes, compressedBytes.length * 10)
    }

  private def countEventsInDecompressedData(decompressedBytes: Array[Byte]): Int =
    TestUtils.parseRecords(decompressedBytes).map(_.length).getOrElse(0)

}

object TestSink {

  def build(maxBytes: Int = 10000, targetBytes: Int = 10000): IO[TestSink] =
    for {
      batchSizes <- Ref[IO].of(List.empty[Int])
      rawEvents  <- Ref[IO].of(List.empty[List[Array[Byte]]])
    } yield new TestSink(batchSizes, rawEvents, maxBytes, targetBytes)

}
