package com.snowplowanalytics.snowplow.collector.core

import cats.effect.{IO, Ref}
import java.nio.ByteBuffer

import com.snowplowanalytics.snowplow.streams.compression.Decompressor

import scala.annotation.tailrec

class TestSink(
  val receivedBatchSizes: Ref[IO, List[Int]],
  val receivedRawEvents: Ref[IO, List[List[Array[Byte]]]],
  val targetBytesCallCount: Ref[IO, Int],
  val maxBytes: Int                          = 10000,
  val targetBytesValue: Int                  = 10000,
  targetBytesSourceOverride: Option[IO[Int]] = None
) extends Sink[IO] {

  override def isHealthy: IO[Boolean] = IO.pure(true)

  /**
    * Normally a fixed value (`targetBytesValue`), but tests exercising the Kinesis-failover
    * scenario can instead supply `targetBytesSourceOverride` (see [[TestSink.buildWithDynamicTargets]])
    * to make the target size change across successive calls.
    */
  override def targetBytes: IO[Int] = targetBytesSourceOverride.getOrElse(IO.pure(targetBytesValue))

  override def storeRawEvents(events: List[Array[Byte]]): IO[Unit] =
    for {
      _ <- receivedBatchSizes.update(_ :+ events.length)
      _ <- receivedRawEvents.update(_ :+ events)
    } yield ()

  /** Decompress and count events in all received compressed batches */
  def getDecompressedEventCount(compressionType: Config.Compression.Type): IO[Int] =
    receivedRawEvents.get.map { batches =>
      batches.map { batch =>
        batch.map(countEvents(_, compressionType)).sum
      }.sum
    }

  private def countEvents(compressedBytes: Array[Byte], compressionType: Config.Compression.Type): Int = {
    val factory = compressionType match {
      case Config.Compression.GZIP => new Decompressor.Gzip(maxBytes)
      case Config.Compression.ZSTD => new Decompressor.Zstd(maxBytes)
    }
    factory.build(ByteBuffer.wrap(compressedBytes)) match {
      case Decompressor.FactorySuccess(decompressor, _) =>
        drainCount(decompressor)
      case other =>
        throw new RuntimeException(s"Can't initialize Decompressor: $other")
    }
  }

  @tailrec
  private def drainCount(d: Decompressor, acc: Int = 0): Int =
    d.getNextRecord match {
      case Decompressor.Record(_) => drainCount(d, acc + 1)
      case Decompressor.EndOfRecords =>
        d.close()
        acc
      case other =>
        d.close()
        throw new RuntimeException(s"Unexpected decompressor result: $other")
    }

}

object TestSink {

  def build(maxBytes: Int = 10000, targetBytes: Int = 10000): IO[TestSink] =
    for {
      batchSizes <- Ref[IO].of(List.empty[Int])
      rawEvents  <- Ref[IO].of(List.empty[List[Array[Byte]]])
      callCount  <- Ref[IO].of(0)
    } yield new TestSink(batchSizes, rawEvents, callCount, maxBytes, targetBytes)

  /**
    * Builds a `TestSink` whose `targetBytes` changes across successive calls, simulating the
    * Kinesis-failover scenario where the sink's advertised target size flips as health changes.
    *
    * Each call to `targetBytes` pops the head of `targets`. Once only one element remains, it is
    * retained and returned on every subsequent call (the list never runs dry).
    *
    * @param maxBytes the fixed maximum payload size for this sink
    * @param targets the sequence of target sizes to hand out, one per call to `targetBytes`
    */
  def buildWithDynamicTargets(maxBytes: Int, targets: List[Int]): IO[TestSink] =
    for {
      batchSizes <- Ref[IO].of(List.empty[Int])
      rawEvents  <- Ref[IO].of(List.empty[List[Array[Byte]]])
      callCount  <- Ref[IO].of(0)
      targetsRef <- Ref[IO].of(targets)
      // Each read both bumps the call counter and pops the next target, so a test can prove the
      // collector re-reads targetBytes per batch (rather than reading once and caching forever).
      targetSource = callCount.update(_ + 1) *> targetsRef.modify {
        case Nil          => (Nil, maxBytes)
        case head :: Nil  => (List(head), head)
        case head :: tail => (tail, head)
      }
    } yield new TestSink(batchSizes, rawEvents, callCount, maxBytes, maxBytes, Some(targetSource))

}
