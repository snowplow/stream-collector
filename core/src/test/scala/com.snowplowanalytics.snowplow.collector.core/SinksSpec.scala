package com.snowplowanalytics.snowplow.collector.core

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.effect.testing.specs2.CatsEffect
import cats.effect.std.Queue
import cats.implicits._
import org.specs2.mutable.Specification

import com.snowplowanalytics.snowplow.collector.thrift.CollectorPayload

import scala.concurrent.duration.DurationLong

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
      emit batch of multiple records when compressed batch size exceeds sink.targetBytes $c6

    Sinks.dequeue with oversized events should:
      emit to BAD sink when payload exceeds good sink's maximum allowed size $c_bad1
      emit batches of 1 to the BAD sink when they arrive at intervals greater than time limit $c_bad2
      emit bigger batches to the BAD sink when payloads arrive at intervals less than time limit $c_bad3
      emit batches to BAD respecting the buffer's recordLimit $c_bad4

    Advanced compression scenarios:
      demonstrate compression efficiency with many events in one batch $c7

    Edge case recovery scenarios:
      recover when single payload cannot compress to targetBytes but fits in maxBytes $c8

  """

  def e1 = {
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue     <- Queue.unbounded[IO, CollectorPayload]
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
      queue     <- Queue.unbounded[IO, CollectorPayload]
      fiber     <- Sinks.dequeue(testConfig(), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(simpleCollectorPayload())
      _         <- IO.sleep(testTimeLimit * 2)
      _         <- queue.offer(simpleCollectorPayload())
      _         <- IO.sleep(testTimeLimit * 2)
      _         <- queue.offer(simpleCollectorPayload())
      _         <- IO.sleep(testTimeLimit * 2)
      _         <- queue.offer(simpleCollectorPayload())
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
      queue     <- Queue.unbounded[IO, CollectorPayload]
      fiber     <- Sinks.dequeue(testConfig(), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(simpleCollectorPayload())
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(simpleCollectorPayload())
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(simpleCollectorPayload())
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(simpleCollectorPayload())
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
      queue     <- Queue.unbounded[IO, CollectorPayload]
      fiber     <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(simpleCollectorPayload())
      _         <- queue.offer(simpleCollectorPayload())
      _         <- queue.offer(simpleCollectorPayload())
      _         <- queue.offer(simpleCollectorPayload())
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
      queue     <- Queue.unbounded[IO, CollectorPayload]
      fiber     <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(simpleCollectorPayload(approximateSize = 700))
      _         <- queue.offer(simpleCollectorPayload(approximateSize = 700))
      _         <- queue.offer(simpleCollectorPayload(approximateSize = 700))
      _         <- queue.offer(simpleCollectorPayload(approximateSize = 700))
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
      queue     <- Queue.unbounded[IO, CollectorPayload]
      fiber     <- Sinks.dequeue(testConfig(), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10))
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
      queue     <- Queue.unbounded[IO, CollectorPayload]
      fiber     <- Sinks.dequeue(testConfig(), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10))
      _         <- IO.sleep(testTimeLimit * 2)
      _         <- queue.offer(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10))
      _         <- IO.sleep(testTimeLimit * 2)
      _         <- queue.offer(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10))
      _         <- IO.sleep(testTimeLimit * 2)
      _         <- queue.offer(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10))
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
      queue     <- Queue.unbounded[IO, CollectorPayload]
      fiber     <- Sinks.dequeue(testConfig(), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10))
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10))
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10))
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10))
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
      queue     <- Queue.unbounded[IO, CollectorPayload]
      fiber     <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10))
      _         <- queue.offer(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10))
      _         <- queue.offer(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10))
      _         <- queue.offer(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10))
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
      queue <- Queue.unbounded[IO, CollectorPayload]
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
      queue <- Queue.unbounded[IO, CollectorPayload]
      compression = testCompression(true)
      fiber                  <- Sinks.dequeue(testConfig(compression = compression), TestUtils.appInfo, queue, sinks).compile.drain.start
      _                      <- queue.offer(simpleCollectorPayload())
      _                      <- IO.sleep(testTimeLimit * 2)
      _                      <- queue.offer(simpleCollectorPayload())
      _                      <- IO.sleep(testTimeLimit * 2)
      _                      <- queue.offer(simpleCollectorPayload())
      _                      <- IO.sleep(testTimeLimit * 2)
      _                      <- queue.offer(simpleCollectorPayload())
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
      queue                  <- Queue.unbounded[IO, CollectorPayload]
      fiber                  <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _                      <- queue.offer(simpleCollectorPayload())
      _                      <- IO.sleep(testTimeLimit * 0.1)
      _                      <- queue.offer(simpleCollectorPayload())
      _                      <- IO.sleep(testTimeLimit * 0.1)
      _                      <- queue.offer(simpleCollectorPayload())
      _                      <- IO.sleep(testTimeLimit * 0.1)
      _                      <- queue.offer(simpleCollectorPayload())
      _                      <- IO.sleep(testTimeLimit * 0.1)
      _                      <- queue.offer(simpleCollectorPayload())
      _                      <- IO.sleep(testTimeLimit * 0.1)
      _                      <- queue.offer(simpleCollectorPayload())
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
      queue                  <- Queue.unbounded[IO, CollectorPayload]
      fiber                  <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _                      <- (1 to 20).toList.traverse((_: Int) => queue.offer(simpleCollectorPayload(10)))
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
      queue                  <- Queue.unbounded[IO, CollectorPayload]
      fiber                  <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _                      <- (1 to 20).toList.traverse((_: Int) => queue.offer(simpleCollectorPayload(10)))
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

  def c6 = {
    val config = testConfig(compression = testCompression(true))
    val io = for {
      goodSink <- TestSink.build(targetBytes = 100)
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue                  <- Queue.unbounded[IO, CollectorPayload]
      fiber                  <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _                      <- (1 to 10).toList.traverse((_: Int) => queue.offer(simpleCollectorPayload(10)))
      _                      <- IO.sleep(testTimeLimit * 2)
      sunkGoods              <- goodSink.receivedBatchSizes.get
      sunkBads               <- badSink.receivedBatchSizes.get
      decompressedEventCount <- goodSink.getDecompressedEventCount(Config.Compression.GZIP)
      _                      <- fiber.cancel
    } yield {
      sunkGoods must beEqualTo(List(2))
      sunkBads must beEmpty
      decompressedEventCount must beEqualTo(10)
    }

    TestControl.executeEmbed(io)
  }

  def c_bad1 = {
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue <- Queue.unbounded[IO, CollectorPayload]
      compression = testCompression(true)
      fiber     <- Sinks.dequeue(testConfig(compression = compression), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2))
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
      queue <- Queue.unbounded[IO, CollectorPayload]
      compression = testCompression(true)
      fiber     <- Sinks.dequeue(testConfig(compression = compression), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2))
      _         <- IO.sleep(testTimeLimit * 2)
      _         <- queue.offer(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2))
      _         <- IO.sleep(testTimeLimit * 2)
      _         <- queue.offer(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2))
      _         <- IO.sleep(testTimeLimit * 2)
      _         <- queue.offer(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2))
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
      queue <- Queue.unbounded[IO, CollectorPayload]
      compression = testCompression(true)
      fiber     <- Sinks.dequeue(testConfig(compression = compression), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2))
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2))
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2))
      _         <- IO.sleep(testTimeLimit * 0.1)
      _         <- queue.offer(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2))
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
      queue     <- Queue.unbounded[IO, CollectorPayload]
      fiber     <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2))
      _         <- queue.offer(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2))
      _         <- queue.offer(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2))
      _         <- queue.offer(nonCompressibleCollectorPayload(approximateSize = goodSink.maxBytes * 2))
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

  def c7 = {
    val config = testConfig(compression = testCompression(true))
    val io = for {
      goodSink <- TestSink.build()
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue                  <- Queue.unbounded[IO, CollectorPayload]
      fiber                  <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start
      _                      <- (1 to 1100).toList.traverse((_: Int) => queue.offer(simpleCollectorPayload(approximateSize = 100)))
      _                      <- IO.sleep(testTimeLimit * 2)
      sunkGoods              <- goodSink.receivedBatchSizes.get
      sunkBads               <- badSink.receivedBatchSizes.get
      decompressedEventCount <- goodSink.getDecompressedEventCount(Config.Compression.GZIP)
      _                      <- fiber.cancel
    } yield {
      sunkGoods must beEqualTo(List(1))
      sunkBads must beEmpty
      decompressedEventCount must beEqualTo(1100)
    }

    TestControl.executeEmbed(io)
  }

  def c8 = {
    // Test edge case: payload can't compress to targetBytes (192KB) but fits in maxBytes (1MB)
    // This simulates the Kinesis rate-limited scenario where targetBytes is reduced to SQS limit
    val config = testConfig(compression = testCompression(true))
    val io = for {
      // Create sink with smaller targetBytes to simulate rate-limited Kinesis
      goodSink <- TestSink.build(maxBytes = 1024 * 1024, targetBytes = 192 * 1024) // 1MB max, 192KB target
      badSink  <- TestSink.build()
      sinks = Sinks(goodSink, badSink)
      queue <- Queue.unbounded[IO, CollectorPayload]
      fiber <- Sinks.dequeue(config, TestUtils.appInfo, queue, sinks).compile.drain.start

      // Create a payload that:
      // 1. Is under 1MB (so should be accepted)
      // 2. Can't compress below 192KB (so initial compression fails)
      // 3. Can compress below 1MB (so retry with maxBytes succeeds)
      bigPayload = nonCompressibleCollectorPayload(approximateSize = 900 * 1024) // 900KB of random data

      _ <- queue.offer(bigPayload)
      _ <- IO.sleep(testTimeLimit * 2)

      sunkGoods              <- goodSink.receivedBatchSizes.get
      sunkBads               <- badSink.receivedBatchSizes.get
      decompressedEventCount <- goodSink.getDecompressedEventCount(Config.Compression.GZIP)
      _                      <- fiber.cancel
    } yield {
      // Should succeed and emit to good sink, not bad sink
      sunkGoods must beEqualTo(List(1))
      sunkBads must beEmpty
      decompressedEventCount must beEqualTo(1)
    }

    TestControl.executeEmbed(io)
  }

}

object SinksSpec {
  val testTimeLimit = 42.seconds

  def testCompression(enabled: Boolean) = Config.Compression(
    enabled              = enabled,
    `type`               = Config.Compression.GZIP,
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
    TestUtils.testConfig.copy(streams = Config.Streams(goodSinkConfig, badSinkConfig)).copy(compression = compression)
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
