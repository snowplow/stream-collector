package com.snowplowanalytics.snowplow.collector.core

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.effect.testing.specs2.CatsEffect
import cats.effect.std.Queue
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
  """

  def e1 = {
    val io = for {
      goodSink <- TestSink.build
      badSink  <- TestSink.build
      sinks = Sinks(goodSink, badSink)
      queue     <- Queue.unbounded[IO, CollectorPayload]
      fiber     <- Sinks.dequeue(testConfig(), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- IO.sleep(1.day)
      sunkGoods <- goodSink.receivedBatchSizes.get
      sunkBads  <- badSink.receivedBatchSizes.get
      _         <- fiber.cancel
    } yield {
      (sunkGoods must beEmpty).and(sunkBads must beEmpty)
    }

    TestControl.executeEmbed(io)
  }

  def e2 = {
    val io = for {
      goodSink <- TestSink.build
      badSink  <- TestSink.build
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
      (sunkGoods must beEqualTo(List(1, 1, 1, 1))).and(sunkBads must beEmpty)
    }

    TestControl.executeEmbed(io)
  }

  def e3 = {
    val io = for {
      goodSink <- TestSink.build
      badSink  <- TestSink.build
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
      (sunkGoods must beEqualTo(List(4))).and(sunkBads must beEmpty)
    }

    TestControl.executeEmbed(io)
  }

  def e4 = {
    val config = testConfig(recordLimit = 2)
    val io = for {
      goodSink <- TestSink.build
      badSink  <- TestSink.build
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
      (sunkGoods must beEqualTo(List(2, 2))).and(sunkBads must beEmpty)
    }

    TestControl.executeEmbed(io)
  }

  def e5 = {
    val config = testConfig(byteLimit = 2000L)
    val io = for {
      goodSink <- TestSink.build
      badSink  <- TestSink.build
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
      (sunkGoods must beEqualTo(List(2, 2))).and(sunkBads must beEmpty)
    }

    TestControl.executeEmbed(io)
  }

  def bad1 = {
    val io = for {
      goodSink <- TestSink.build
      badSink  <- TestSink.build
      sinks = Sinks(goodSink, badSink)
      queue     <- Queue.unbounded[IO, CollectorPayload]
      fiber     <- Sinks.dequeue(testConfig(), TestUtils.appInfo, queue, sinks).compile.drain.start
      _         <- queue.offer(simpleCollectorPayload(approximateSize = goodSink.maxBytes * 10))
      _         <- IO.sleep(testTimeLimit * 2)
      sunkGoods <- goodSink.receivedBatchSizes.get
      sunkBads  <- badSink.receivedBatchSizes.get
      _         <- fiber.cancel
    } yield {
      (sunkGoods must beEmpty).and(sunkBads must beEqualTo(List(1)))
    }

    TestControl.executeEmbed(io)
  }

  def bad2 = {
    val io = for {
      goodSink <- TestSink.build
      badSink  <- TestSink.build
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
      (sunkGoods must beEmpty).and(sunkBads must beEqualTo(List(1, 1, 1, 1)))
    }

    TestControl.executeEmbed(io)
  }

  def bad3 = {
    val io = for {
      goodSink <- TestSink.build
      badSink  <- TestSink.build
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
      (sunkGoods must beEmpty).and(sunkBads must beEqualTo(List(4)))
    }

    TestControl.executeEmbed(io)
  }

  def bad4 = {
    val config = testConfig(recordLimit = 2)
    val io = for {
      goodSink <- TestSink.build
      badSink  <- TestSink.build
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
      (sunkGoods must beEmpty).and(sunkBads must beEqualTo(List(2, 2)))
    }

    TestControl.executeEmbed(io)
  }

}

object SinksSpec {
  val testTimeLimit = 42.seconds

  def testConfig(recordLimit: Long = 1000L, byteLimit: Long = 100000L) = {
    val buffer         = Config.Buffer(byteLimit                       = byteLimit, recordLimit = recordLimit, timeLimit = testTimeLimit.toMillis)
    val goodSinkConfig = TestUtils.testConfig.streams.good.copy(buffer = buffer)
    val badSinkConfig  = TestUtils.testConfig.streams.bad.copy(buffer  = buffer)
    TestUtils.testConfig.copy(streams = Config.Streams(goodSinkConfig, badSinkConfig))
  }

  def simpleCollectorPayload(approximateSize: Int = 10): CollectorPayload = {
    val cp = new CollectorPayload()
    cp.setBody(Array.fill(approximateSize)('X'.toByte))
    cp
  }
}
