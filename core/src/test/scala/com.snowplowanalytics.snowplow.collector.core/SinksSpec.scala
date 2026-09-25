package com.snowplowanalytics.snowplow.collector.core

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.effect.testing.specs2.CatsEffect
import cats.effect.std.Queue
import cats.implicits._
import org.specs2.mutable.Specification

import com.snowplowanalytics.snowplow.collector.thrift.CollectorPayload

import scala.concurrent.duration.{DurationLong, FiniteDuration}

class SinksSpec extends Specification with CatsEffect {
  import SinksSpec._

  override def is = s2"""
  Sinks.dequeue with good events should:
    emit nothing to the sinks when the queue is empty $e1
    emit batches of 1 to the GOOD sink when they arrive at intervals greater than time limit $e2
    emit bigger batches to the GOOD sink when payloads arrive at intervals less than time limit $e3
    emit batches respecting the buffer's recordLimit $e4
    emit batches respecting the buffer's byteLimit $e5

  Sinks.dequeue with oversized events should:
    emit to BAD sink when payload exceeds good sink's maximum allowed size $bad1
    emit batches of 1 to the BAD sink when they arrive at intervals greater than time limit $bad2
    emit bigger batches to the BAD sink when payloads arrive at intervals less than time limit $bad3
    emit batches to BAD respecting the buffer's recordLimit $bad4

  When compression is enabled:
    Sinks.dequeue with good events should:
      emit nothing to the sinks when the queue is empty $c1
      emit batches of 1 to the GOOD sink when they arrive at intervals greater than time limit $c2
      emit bigger batches to the GOOD sink when payloads arrive at intervals less than time limit $c3
      demonstrate recordLimit applies to compressed batches, not individual events $c4
      demonstrate byteLimit applies to compressed data size, not original size $c5
      emit batch of multiple records when compressed batch size exceeds sink.targetBytes (gzip) $c6_gzip
      emit batch of multiple records when compressed batch size exceeds sink.targetBytes (zstd) $c6_zstd

    Sinks.dequeue with oversized events should:
      emit to BAD sink when payload exceeds good sink's maximum allowed size $c_bad1
      emit batches of 1 to the BAD sink when they arrive at intervals greater than time limit $c_bad2
      emit bigger batches to the BAD sink when payloads arrive at intervals less than time limit $c_bad3
      emit batches to BAD respecting the buffer's recordLimit $c_bad4

    Advanced compression scenarios:
      demonstrate compression efficiency with many events in one batch (gzip) $c7_gzip
      demonstrate compression efficiency with many events in one batch (zstd) $c7_zstd

    Edge case recovery scenarios:
      recover when single payload cannot compress to targetBytes but fits in maxBytes (gzip) $c8_gzip
      recover when single payload cannot compress to targetBytes but fits in maxBytes (zstd) $c8_zstd

    Cross-batch state contamination guards on the shared, reused compressor:
      good events before AND after a size violation all round-trip correctly (gzip) $c_interleave_gzip
      good events before AND after a size violation all round-trip correctly (zstd) $c_interleave_zstd
      all events round-trip when the sink's target size changes across batches (gzip) $c_dynamic_target_gzip
      all events round-trip when the sink's target size changes across batches (zstd) $c_dynamic_target_zstd

  On shutdown, when the queue is terminated with a None, Sinks.dequeue should:
    flush a partially-filled batch to the GOOD sink $shutdown1
    flush a partially-filled batch to the BAD sink $shutdown2
    drain events that are still waiting in the queue $shutdown3
    flush a partially-filled compressed batch to the GOOD sink $shutdown4
    not complete until the sink has finished writing $shutdown5

  """

  def e1 = {
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue     <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber     <- Sinks.dequeue(testConfig(), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- IO.sleep(1.day)
      sunkGoods <- goodSink.receivedBatchSizes.get
      sunkBads  <- badSink.receivedBatchSizes.get
      _         <- fiber.cancel
    } yield {
      sunkGoods must beEmpty
      sunkBads must beEmpty
    }

    TestControl.executeEmbed(io)
  }

  def e2 = {
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue     <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber     <- Sinks.dequeue(testConfig(), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(Some(simpleCollectorPayload()))
      _         <- IO.sleep(testTimeLimit * 2)
      _         <- queue.offer(Some(simpleCollectorPayload()))
      _         <- IO.sleep(testTimeLimit * 2)
      _         <- queue.offer(Some(simpleCollectorPayload()))
      _         <- IO.sleep(testTimeLimit * 2)
      _         <- queue.offer(Some(simpleCollectorPayload()))
      _         <- IO.sleep(testTimeLimit * 2)
      sunkGoods <- goodSink.receivedBatchSizes.get
      sunkBads  <- badSink.receivedBatchSizes.get
      _         <- fiber.cancel
    } yield {
      sunkGoods must beEqualTo(List(1, 1, 1, 1))
      sunkBads must beEmpty
    }

    TestControl.executeEmbed(io)
  }

  def e3 = {
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue     <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber     <- Sinks.dequeue(testConfig(), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(Some(simpleCollectorPayload()))
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(Some(simpleCollectorPayload()))
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(Some(simpleCollectorPayload()))
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(Some(simpleCollectorPayload()))
      _         <- IO.sleep(testTimeLimit)
      sunkGoods <- goodSink.receivedBatchSizes.get
      sunkBads  <- badSink.receivedBatchSizes.get
      _         <- fiber.cancel
    } yield {
      sunkGoods must beEqualTo(List(4))
      sunkBads must beEmpty
    }

    TestControl.executeEmbed(io)
  }

  def e4 = {
    val config = testConfig(recordLimit = 2)
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue     <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber     <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(Some(simpleCollectorPayload()))
      _         <- queue.offer(Some(simpleCollectorPayload()))
      _         <- queue.offer(Some(simpleCollectorPayload()))
      _         <- queue.offer(Some(simpleCollectorPayload()))
      _         <- IO.sleep(testTimeLimit * 2)
      sunkGoods <- goodSink.receivedBatchSizes.get
      sunkBads  <- badSink.receivedBatchSizes.get
      _         <- fiber.cancel
    } yield {
      sunkGoods must beEqualTo(List(2, 2))
      sunkBads must beEmpty
    }

    TestControl.executeEmbed(io)
  }

  def e5 = {
    val config = testConfig(byteLimit = 2000L)
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue     <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber     <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(Some(simpleCollectorPayload(approximateSize = 700)))
      _         <- queue.offer(Some(simpleCollectorPayload(approximateSize = 700)))
      _         <- queue.offer(Some(simpleCollectorPayload(approximateSize = 700)))
      _         <- queue.offer(Some(simpleCollectorPayload(approximateSize = 700)))
      _         <- IO.sleep(testTimeLimit * 2)
      sunkGoods <- goodSink.receivedBatchSizes.get
      sunkBads  <- badSink.receivedBatchSizes.get
      _         <- fiber.cancel
    } yield {
      sunkGoods must beEqualTo(List(2, 2))
      sunkBads must beEmpty
    }

    TestControl.executeEmbed(io)
  }

  def bad1 = {
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue     <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber     <- Sinks.dequeue(testConfig(), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(Some(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10)))
      _         <- IO.sleep(testTimeLimit * 2)
      sunkGoods <- goodSink.receivedBatchSizes.get
      sunkBads  <- badSink.receivedBatchSizes.get
      _         <- fiber.cancel
    } yield {
      sunkGoods must beEmpty
      sunkBads must beEqualTo(List(1))
    }

    TestControl.executeEmbed(io)
  }

  def bad2 = {
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue     <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber     <- Sinks.dequeue(testConfig(), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(Some(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10)))
      _         <- IO.sleep(testTimeLimit * 2)
      _         <- queue.offer(Some(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10)))
      _         <- IO.sleep(testTimeLimit * 2)
      _         <- queue.offer(Some(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10)))
      _         <- IO.sleep(testTimeLimit * 2)
      _         <- queue.offer(Some(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10)))
      _         <- IO.sleep(testTimeLimit * 2)
      sunkGoods <- goodSink.receivedBatchSizes.get
      sunkBads  <- badSink.receivedBatchSizes.get
      _         <- fiber.cancel
    } yield {
      sunkGoods must beEmpty
      sunkBads must beEqualTo(List(1, 1, 1, 1))
    }

    TestControl.executeEmbed(io)
  }

  def bad3 = {
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue     <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber     <- Sinks.dequeue(testConfig(), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(Some(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10)))
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(Some(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10)))
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(Some(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10)))
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(Some(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10)))
      _         <- IO.sleep(testTimeLimit * 2)
      sunkGoods <- goodSink.receivedBatchSizes.get
      sunkBads  <- badSink.receivedBatchSizes.get
      _         <- fiber.cancel
    } yield {
      sunkGoods must beEmpty
      sunkBads must beEqualTo(List(4))
    }

    TestControl.executeEmbed(io)
  }

  def bad4 = {
    val config = testConfig(recordLimit = 2)
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue     <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber     <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(Some(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10)))
      _         <- queue.offer(Some(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10)))
      _         <- queue.offer(Some(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10)))
      _         <- queue.offer(Some(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10)))
      _         <- IO.sleep(testTimeLimit * 2)
      sunkGoods <- goodSink.receivedBatchSizes.get
      sunkBads  <- badSink.receivedBatchSizes.get
      _         <- fiber.cancel
    } yield {
      sunkGoods must beEmpty
      sunkBads must beEqualTo(List(2, 2))
    }

    TestControl.executeEmbed(io)
  }

  def c1 = {
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue <- Queue.unbounded[IO, Option[CollectorPayload]]
      compression = testCompression(true)
      fiber                  <- Sinks.dequeue(testConfig(compression = compression), TestUtils.appInfo, queue, sinks).compile.drain.start
      _                      <- IO.sleep(1.day)
      sunkGoods              <- goodSink.receivedBatchSizes.get
      sunkBads               <- badSink.receivedBatchSizes.get
      decompressedEventCount <- goodSink.getDecompressedEventCount(Config.Compression.GZIP)
      _                      <- fiber.cancel
    } yield {
      sunkGoods must beEmpty
      sunkBads must beEmpty
      decompressedEventCount must beEqualTo(0)
    }

    TestControl.executeEmbed(io)
  }

  def c2 = {
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue <- Queue.unbounded[IO, Option[CollectorPayload]]
      compression = testCompression(true)
      fiber                  <- Sinks.dequeue(testConfig(compression = compression), TestUtils.appInfo, queue, sinks).compile.drain.start
      _                      <- queue.offer(Some(simpleCollectorPayload()))
      _                      <- IO.sleep(testTimeLimit * 2)
      _                      <- queue.offer(Some(simpleCollectorPayload()))
      _                      <- IO.sleep(testTimeLimit * 2)
      _                      <- queue.offer(Some(simpleCollectorPayload()))
      _                      <- IO.sleep(testTimeLimit * 2)
      _                      <- queue.offer(Some(simpleCollectorPayload()))
      _                      <- IO.sleep(testTimeLimit * 2)
      sunkGoods              <- goodSink.receivedBatchSizes.get
      sunkBads               <- badSink.receivedBatchSizes.get
      decompressedEventCount <- goodSink.getDecompressedEventCount(Config.Compression.GZIP)
      _                      <- fiber.cancel
    } yield {
      sunkGoods must beEqualTo(List(1, 1, 1, 1))
      sunkBads must beEmpty
      decompressedEventCount must beEqualTo(4)
    }

    TestControl.executeEmbed(io)
  }

  def c3 = {
    val config = testConfig(compression = testCompression(true))
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue                  <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber                  <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _                      <- queue.offer(Some(simpleCollectorPayload()))
      _                      <- IO.sleep(testTimeLimit * 0.1)
      _                      <- queue.offer(Some(simpleCollectorPayload()))
      _                      <- IO.sleep(testTimeLimit * 0.1)
      _                      <- queue.offer(Some(simpleCollectorPayload()))
      _                      <- IO.sleep(testTimeLimit * 0.1)
      _                      <- queue.offer(Some(simpleCollectorPayload()))
      _                      <- IO.sleep(testTimeLimit * 0.1)
      _                      <- queue.offer(Some(simpleCollectorPayload()))
      _                      <- IO.sleep(testTimeLimit * 0.1)
      _                      <- queue.offer(Some(simpleCollectorPayload()))
      _                      <- IO.sleep(testTimeLimit)
      sunkGoods              <- goodSink.receivedBatchSizes.get
      sunkBads               <- badSink.receivedBatchSizes.get
      decompressedEventCount <- goodSink.getDecompressedEventCount(Config.Compression.GZIP)
      _                      <- fiber.cancel
    } yield {
      sunkGoods must beEqualTo(List(1))
      sunkBads must beEmpty
      decompressedEventCount must beEqualTo(6)
    }

    TestControl.executeEmbed(io)
  }

  def c4 = {
    // Test recordLimit behavior: with compression, recordLimit applies to compressed batches
    val config = testConfig(recordLimit = 2, compression = testCompression(true))
    val io = for {
      goodSink <- TestSink.build(targetBytes = 100)
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue                  <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber                  <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _                      <- (1 to 20).toList.traverse((_: Int) => queue.offer(Some(simpleCollectorPayload(10))))
      _                      <- IO.sleep(testTimeLimit * 2)
      sunkGoods              <- goodSink.receivedBatchSizes.get
      sunkBads               <- badSink.receivedBatchSizes.get
      decompressedEventCount <- goodSink.getDecompressedEventCount(Config.Compression.GZIP)
      _                      <- fiber.cancel
    } yield {
      sunkGoods must beEqualTo(List(2, 1))
      sunkBads must beEmpty
      decompressedEventCount must beEqualTo(20)
    }

    TestControl.executeEmbed(io)
  }

  def c5 = {
    // Test byteLimit behavior: with compression, byteLimit applies to compressed data size
    val config = testConfig(byteLimit = 200L, compression = testCompression(true))
    val io = for {
      goodSink <- TestSink.build(targetBytes = 100)
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue                  <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber                  <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _                      <- (1 to 20).toList.traverse((_: Int) => queue.offer(Some(simpleCollectorPayload(10))))
      _                      <- IO.sleep(testTimeLimit * 2)
      sunkGoods              <- goodSink.receivedBatchSizes.get
      sunkBads               <- badSink.receivedBatchSizes.get
      decompressedEventCount <- goodSink.getDecompressedEventCount(Config.Compression.GZIP)
      _                      <- fiber.cancel
    } yield {
      sunkGoods must beEqualTo(List(2, 1))
      sunkBads must beEmpty
      decompressedEventCount must beEqualTo(20)
    }

    TestControl.executeEmbed(io)
  }

  /**
    * Shared runner for gap #1 (c6/c7/c8): drives `Sinks.dequeue` with compression enabled for the
    * given codec and returns the raw observations, so that gzip and zstd variants can each apply
    * their own assertions (gzip: exact known-good lists; zstd: data-integrity invariants, since the
    * compressed-batch split depends on the codec's compression ratio).
    */
  private def runC6(tpe: Config.Compression.Type): IO[(List[Int], List[Int], Int)] = {
    val config = testConfig(compression = testCompression(true, tpe))
    for {
      goodSink <- TestSink.build(targetBytes = 100)
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue                  <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber                  <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _                      <- (1 to 10).toList.traverse((_: Int) => queue.offer(Some(simpleCollectorPayload(10))))
      _                      <- IO.sleep(testTimeLimit * 2)
      sunkGoods              <- goodSink.receivedBatchSizes.get
      sunkBads               <- badSink.receivedBatchSizes.get
      decompressedEventCount <- goodSink.getDecompressedEventCount(tpe)
      _                      <- fiber.cancel
    } yield (sunkGoods, sunkBads, decompressedEventCount)
  }

  def c6_gzip =
    TestControl.executeEmbed(runC6(Config.Compression.GZIP)).map {
      case (sunkGoods, sunkBads, decompressedEventCount) =>
        sunkGoods must beEqualTo(List(2))
        sunkBads must beEmpty
        decompressedEventCount must beEqualTo(10)
    }

  def c6_zstd =
    TestControl.executeEmbed(runC6(Config.Compression.ZSTD)).map {
      case (sunkGoods, sunkBads, decompressedEventCount) =>
        sunkBads must beEmpty
        decompressedEventCount must beEqualTo(10)
        sunkGoods.sum must be_>=(1)
    }

  def c_bad1 = {
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue <- Queue.unbounded[IO, Option[CollectorPayload]]
      compression = testCompression(true)
      fiber     <- Sinks.dequeue(testConfig(compression = compression), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(Some(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2)))
      _         <- IO.sleep(testTimeLimit * 2)
      sunkGoods <- goodSink.receivedBatchSizes.get
      sunkBads  <- badSink.receivedBatchSizes.get
      _         <- fiber.cancel
    } yield {
      sunkGoods must beEmpty
      sunkBads must beEqualTo(List(1))
    }

    TestControl.executeEmbed(io)
  }

  def c_bad2 = {
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue <- Queue.unbounded[IO, Option[CollectorPayload]]
      compression = testCompression(true)
      fiber     <- Sinks.dequeue(testConfig(compression = compression), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(Some(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2)))
      _         <- IO.sleep(testTimeLimit * 2)
      _         <- queue.offer(Some(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2)))
      _         <- IO.sleep(testTimeLimit * 2)
      _         <- queue.offer(Some(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2)))
      _         <- IO.sleep(testTimeLimit * 2)
      _         <- queue.offer(Some(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2)))
      _         <- IO.sleep(testTimeLimit * 2)
      sunkGoods <- goodSink.receivedBatchSizes.get
      sunkBads  <- badSink.receivedBatchSizes.get
      _         <- fiber.cancel
    } yield {
      sunkGoods must beEmpty
      sunkBads must beEqualTo(List(1, 1, 1, 1))
    }

    TestControl.executeEmbed(io)
  }

  def c_bad3 = {
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue <- Queue.unbounded[IO, Option[CollectorPayload]]
      compression = testCompression(true)
      fiber     <- Sinks.dequeue(testConfig(compression = compression), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(Some(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2)))
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(Some(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2)))
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(Some(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2)))
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(Some(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2)))
      _         <- IO.sleep(testTimeLimit * 2)
      sunkGoods <- goodSink.receivedBatchSizes.get
      sunkBads  <- badSink.receivedBatchSizes.get
      _         <- fiber.cancel
    } yield {
      sunkGoods must beEmpty
      sunkBads must beEqualTo(List(4))
    }

    TestControl.executeEmbed(io)
  }

  def c_bad4 = {
    val config = testConfig(recordLimit = 2, compression = testCompression(true))
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue     <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber     <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(Some(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2)))
      _         <- queue.offer(Some(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2)))
      _         <- queue.offer(Some(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2)))
      _         <- queue.offer(Some(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2)))
      _         <- IO.sleep(testTimeLimit * 2)
      sunkGoods <- goodSink.receivedBatchSizes.get
      sunkBads  <- badSink.receivedBatchSizes.get
      _         <- fiber.cancel
    } yield {
      sunkGoods must beEmpty
      sunkBads must beEqualTo(List(2, 2))
    }

    TestControl.executeEmbed(io)
  }

  private def runC7(tpe: Config.Compression.Type): IO[(List[Int], List[Int], Int)] = {
    val config = testConfig(compression = testCompression(true, tpe))
    for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue                  <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber                  <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _                      <- (1 to 1100).toList.traverse((_: Int) => queue.offer(Some(simpleCollectorPayload(approximateSize = 100))))
      _                      <- IO.sleep(testTimeLimit * 2)
      sunkGoods              <- goodSink.receivedBatchSizes.get
      sunkBads               <- badSink.receivedBatchSizes.get
      decompressedEventCount <- goodSink.getDecompressedEventCount(tpe)
      _                      <- fiber.cancel
    } yield (sunkGoods, sunkBads, decompressedEventCount)
  }

  def c7_gzip =
    TestControl.executeEmbed(runC7(Config.Compression.GZIP)).map {
      case (sunkGoods, sunkBads, decompressedEventCount) =>
        sunkGoods must beEqualTo(List(1))
        sunkBads must beEmpty
        decompressedEventCount must beEqualTo(1100)
    }

  def c7_zstd =
    TestControl.executeEmbed(runC7(Config.Compression.ZSTD)).map {
      case (sunkGoods, sunkBads, decompressedEventCount) =>
        sunkBads must beEmpty
        decompressedEventCount must beEqualTo(1100)
        sunkGoods.sum must be_>=(1)
    }

  // Test edge case: payload can't compress to targetBytes (192KB) but fits in maxBytes (1MB)
  // This simulates the Kinesis rate-limited scenario where targetBytes is reduced to SQS limit
  private def runC8(tpe: Config.Compression.Type): IO[(List[Int], List[Int], Int)] = {
    val config = testConfig(compression = testCompression(true, tpe))
    for {
      // Create sink with smaller targetBytes to simulate rate-limited Kinesis
      goodSink <- TestSink.build(maxBytes = 1024 * 1024, targetBytes = 192 * 1024) // 1MB max, 192KB target
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start

      // Create a payload that:
      // 1. Is under 1MB (so should be accepted)
      // 2. Can't compress below 192KB (so initial compression fails)
      // 3. Can compress below 1MB (so retry with maxBytes succeeds)
      bigPayload = nonCompressibleCollectorPayload(approximateSize = 900 * 1024) // 900KB of random data

      _ <- queue.offer(Some(bigPayload))
      _ <- IO.sleep(testTimeLimit * 2)

      sunkGoods              <- goodSink.receivedBatchSizes.get
      sunkBads               <- badSink.receivedBatchSizes.get
      decompressedEventCount <- goodSink.getDecompressedEventCount(tpe)
      _                      <- fiber.cancel
    } yield (sunkGoods, sunkBads, decompressedEventCount)
  }

  def c8_gzip =
    TestControl.executeEmbed(runC8(Config.Compression.GZIP)).map {
      case (sunkGoods, sunkBads, decompressedEventCount) =>
        // Should succeed and emit to good sink, not bad sink
        sunkGoods must beEqualTo(List(1))
        sunkBads must beEmpty
        decompressedEventCount must beEqualTo(1)
    }

  def c8_zstd =
    TestControl.executeEmbed(runC8(Config.Compression.ZSTD)).map {
      case (sunkGoods, sunkBads, decompressedEventCount) =>
        // A single input payload can never be split across multiple compressed chunks or sink
        // writes, regardless of codec, so the exact List(1) is a structural guarantee here (not a
        // compression-ratio artifact).
        sunkGoods must beEqualTo(List(1))
        sunkBads must beEmpty
        decompressedEventCount must beEqualTo(1)
    }

  /**
    * Gap #2: interleave good and oversized payloads through the same, reused compressor.
    *
    * A size violation forces the shared compressor to extract whatever good records it was
    * holding and to reset for the next payload. This proves that reset doesn't corrupt the good
    * events that were already accumulated, nor the good events that arrive afterwards.
    */
  private def testInterleaving(tpe: Config.Compression.Type) = {
    val config = testConfig(compression = testCompression(true, tpe))
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      good      = simpleCollectorPayload(10)
      oversized = nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2)
      _                      <- queue.offer(Some(good))
      _                      <- queue.offer(Some(good))
      _                      <- queue.offer(Some(oversized))
      _                      <- queue.offer(Some(good))
      _                      <- queue.offer(Some(good))
      _                      <- queue.offer(Some(oversized))
      _                      <- queue.offer(Some(good))
      _                      <- IO.sleep(testTimeLimit * 2)
      sunkBads               <- badSink.receivedBatchSizes.get
      decompressedEventCount <- goodSink.getDecompressedEventCount(tpe)
      _                      <- fiber.cancel
    } yield {
      decompressedEventCount must beEqualTo(5)
      sunkBads.sum must beEqualTo(2)
    }

    TestControl.executeEmbed(io)
  }

  def c_interleave_gzip = testInterleaving(Config.Compression.GZIP)
  def c_interleave_zstd = testInterleaving(Config.Compression.ZSTD)

  /**
    * Gap #3: the sink's target size changes across batches on the reused compressor, exactly as
    * happens during Kinesis failover (`reset(payloadVersion, targetSize)` is called with a new
    * target for every new batch). This proves data integrity holds as the target flips between a
    * small and a large value.
    */
  private def testDynamicTarget(tpe: Config.Compression.Type) = {
    val config = testConfig(compression = testCompression(true, tpe))
    val io = for {
      goodSink <- TestSink.buildWithDynamicTargets(maxBytes = 100000, targets = List(100, 100000, 100, 100000))
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue                  <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber                  <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _                      <- (1 to 30).toList.traverse((_: Int) => queue.offer(Some(simpleCollectorPayload(10))))
      _                      <- IO.sleep(testTimeLimit * 2)
      sunkBads               <- badSink.receivedBatchSizes.get
      decompressedEventCount <- goodSink.getDecompressedEventCount(tpe)
      targetBytesCallCount   <- goodSink.targetBytesCallCount.get
      _                      <- fiber.cancel
    } yield {
      decompressedEventCount must beEqualTo(30)
      sunkBads must beEmpty
      // The scenario forms more than one batch, so the collector must re-read targetBytes at
      // least twice. A "read once and cache" regression would leave this at 1 and fail here.
      targetBytesCallCount must beGreaterThanOrEqualTo(2)
    }

    TestControl.executeEmbed(io)
  }

  def c_dynamic_target_gzip = testDynamicTarget(Config.Compression.GZIP)
  def c_dynamic_target_zstd = testDynamicTarget(Config.Compression.ZSTD)

  def shutdown1 = {
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber <- Sinks.dequeue(testConfig(), TestUtils.appInfo, queue, sinks).compile.drain.start
      _     <- queue.offer(Some(simpleCollectorPayload()))
      _     <- queue.offer(Some(simpleCollectorPayload()))
      // Terminate well before the time limit, so the batch is still pending
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(None)
      _         <- fiber.join
      sunkGoods <- goodSink.receivedBatchSizes.get
      sunkBads  <- badSink.receivedBatchSizes.get
    } yield {
      sunkGoods must beEqualTo(List(2))
      sunkBads must beEmpty
    }

    TestControl.executeEmbed(io)
  }

  def shutdown2 = {
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue     <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber     <- Sinks.dequeue(testConfig(), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(Some(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10)))
      _         <- queue.offer(Some(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10)))
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(None)
      _         <- fiber.join
      sunkGoods <- goodSink.receivedBatchSizes.get
      sunkBads  <- badSink.receivedBatchSizes.get
    } yield {
      sunkGoods must beEmpty
      sunkBads must beEqualTo(List(2))
    }

    TestControl.executeEmbed(io)
  }

  def shutdown3 = {
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue <- Queue.unbounded[IO, Option[CollectorPayload]]
      // Fill the queue *before* the dequeue loop starts, so there is a genuine backlog to drain
      _         <- List.fill(4)(simpleCollectorPayload()).traverse_(cp => queue.offer(Some(cp)))
      _         <- queue.offer(None)
      fiber     <- Sinks.dequeue(testConfig(), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- fiber.join
      sunkGoods <- goodSink.receivedBatchSizes.get
      sunkBads  <- badSink.receivedBatchSizes.get
    } yield {
      sunkGoods must beEqualTo(List(4))
      sunkBads must beEmpty
    }

    TestControl.executeEmbed(io)
  }

  def shutdown4 = {
    val config = testConfig(compression = testCompression(true))
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _     <- queue.offer(Some(simpleCollectorPayload()))
      _     <- queue.offer(Some(simpleCollectorPayload()))
      // Terminate while both events are still inside the compressor's current frame
      _                      <- IO.sleep(testTimeLimit * 0.1)
      _                      <- queue.offer(None)
      _                      <- fiber.join
      sunkGoods              <- goodSink.receivedBatchSizes.get
      sunkBads               <- badSink.receivedBatchSizes.get
      decompressedEventCount <- goodSink.getDecompressedEventCount(Config.Compression.GZIP)
    } yield {
      sunkGoods must beEqualTo(List(1))
      sunkBads must beEmpty
      decompressedEventCount must beEqualTo(2)
    }

    TestControl.executeEmbed(io)
  }

  def shutdown5 = {
    val sinkDelay = 10.seconds
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(slowSink(goodSink, sinkDelay), badSink)
      queue     <- Queue.unbounded[IO, Option[CollectorPayload]]
      fiber     <- Sinks.dequeue(testConfig(), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(Some(simpleCollectorPayload()))
      _         <- queue.offer(Some(simpleCollectorPayload()))
      _         <- IO.sleep(testTimeLimit * 0.1)
      before    <- IO.monotonic
      _         <- queue.offer(None)
      _         <- fiber.join
      after     <- IO.monotonic
      sunkGoods <- goodSink.receivedBatchSizes.get
    } yield {
      // The Supervisor is configured with `await = true`, so the stream does not complete until the
      // fibers it started have finished writing.
      sunkGoods must beEqualTo(List(2))
      (after - before) must beGreaterThanOrEqualTo(sinkDelay)
    }

    TestControl.executeEmbed(io)
  }

}

object SinksSpec {
  val testTimeLimit = 42.seconds

  def testCompression(
    enabled: Boolean,
    `type`: Config.Compression.Type = Config.Compression.GZIP
  ) = Config.Compression(
    enabled              = enabled,
    `type`               = `type`,
    gzipCompressionLevel = 6,
    zstdCompressionLevel = 3
  )

  val defaultCompression = testCompression(false)

  def testConfig(
    recordLimit: Long               = 1000L,
    byteLimit: Long                 = 100000L,
    compression: Config.Compression = defaultCompression
  ) = {
    val buffer         = Config.Buffer(byteLimit                       = byteLimit, recordLimit = recordLimit, timeLimit = testTimeLimit.toMillis)
    val goodSinkConfig = TestUtils.testConfig.streams.good.copy(buffer = buffer)
    val badSinkConfig  = TestUtils.testConfig.streams.bad.copy(buffer  = buffer)
    TestUtils
      .testConfig
      .copy(streams = Config.Streams(goodSinkConfig, badSinkConfig, None))
      .copy(compression = compression)
  }

  /** Wraps a sink so each write takes `delay`, to prove the drain waits for writes to finish */
  def slowSink(underlying: Sink[IO], delay: FiniteDuration): Sink[IO] =
    new Sink[IO] {
      override val maxBytes: Int          = underlying.maxBytes
      override def targetBytes: IO[Int]   = underlying.targetBytes
      override def isHealthy: IO[Boolean] = underlying.isHealthy
      override def storeRawEvents(events: List[Array[Byte]]): IO[Unit] =
        IO.sleep(delay) >> underlying.storeRawEvents(events)
    }

  def simpleCollectorPayload(approximateSize: Int = 10): CollectorPayload = {
    val cp = new CollectorPayload()
    cp.setBody(Array.fill(approximateSize)('X'.toByte))
    cp
  }

  /**
    * Creates a CollectorPayload with non-compressible data for testing compression scenarios.
    *
    * Unlike [[simpleCollectorPayload]] which uses repeated characters that compress extremely well
    * (e.g., 1MB of 'X' chars → ~50 bytes compressed), this function generates pseudo-random data
    * that resists compression (e.g., 20KB → ~19.6KB compressed).
    *
    * This is essential for testing "bad sink" behavior when compression is enabled - we need
    * payloads that remain oversized even after compression to properly test the bad sink path.
    *
    * @param approximateSize the desired size of the payload body in bytes
    * @return a CollectorPayload with deterministic pseudo-random data that compresses poorly
    *
    * @note Uses a fixed seed (42) to ensure deterministic test behavior - the same input size
    *       always produces the identical byte sequence, making tests reproducible across runs.
    */
  def nonCompressibleCollectorPayload(approximateSize: Int): CollectorPayload = {
    val cp          = new CollectorPayload()
    val random      = new scala.util.Random(42) // Fixed seed for deterministic tests
    val randomBytes = Array.fill(approximateSize)(random.nextInt(256).toByte)
    cp.setBody(randomBytes)
    cp
  }

}
