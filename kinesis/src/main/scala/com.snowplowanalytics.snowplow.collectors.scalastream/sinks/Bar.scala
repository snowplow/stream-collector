package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import scala.collection.JavaConverters._
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import scala.concurrent.duration._
import scala.util.control.NonFatal

object Bar extends App {

  val RetryPeriod = 2.seconds

  case class IntWithCounter(
    x: Int,
    kinesisCounter: Int,
    sqsCounter: Int,
    nanoTime: Long,
    lastRetryNanoTime: Long
  ) {
    override def toString = s"Int($x, $kinesisCounter, $sqsCounter)"
  }

  val executorService = new ScheduledThreadPoolExecutor(4)
  val q = new ConcurrentLinkedQueue[IntWithCounter]

  val kinesisQ = new ConcurrentLinkedQueue[IntWithCounter]
  val sqsQ = new ConcurrentLinkedQueue[IntWithCounter]

  val kinesisBuffer = new ConcurrentLinkedQueue[IntWithCounter]
  val sqsBuffer = new ConcurrentLinkedQueue[IntWithCounter]

  q.addAll((1 to 100).toList.map(x => (IntWithCounter(x, 3, 10, 0L, System.nanoTime))).asJava)

  scala.sys.addShutdownHook {
    println(s"shutting down with shutdown hook")
    println(s"size of queue: ${q.size}")
    println(s"size of queue: ${kinesisQ.size}")
    println(s"size of queue: ${sqsQ.size}")
    println(s"size of queue: ${kinesisBuffer.size}")
    println(s"size of queue: ${sqsBuffer.size}")
  }

  executorService.scheduleAtFixedRate(
    () => logNonFatalAndKeepRunning(bar),
    100,
    10,
    MILLISECONDS
  )

  executorService.scheduleAtFixedRate(
    () => logNonFatalAndKeepRunning(bufferedKinesis),
    500,
    10,
    MILLISECONDS
  )

  executorService.scheduleAtFixedRate(
    () => logNonFatalAndKeepRunning(bufferedSqs),
    600,
    10,
    MILLISECONDS
  )

  def logNonFatalAndKeepRunning(func: () => Unit): Unit =
    try {
      func()
    } catch {
      case NonFatal(e) =>
        println(s"ERROR: ${e.getMessage}")
    }

  def bar() =
    Option(q.poll()).foreach { head =>
      if (head.kinesisCounter > 0) kinesisQ.add(head)
      else if (head.sqsCounter > 0) sqsQ.add(head)
      else println(s"Dropping $head")
    }

  def buffer(
    from: ConcurrentLinkedQueue[IntWithCounter],
    to: ConcurrentLinkedQueue[IntWithCounter],
    max: Int,
    maxDuration: FiniteDuration,
    func: () => Unit
  ) = {
    val peekNano = Option(to.peek).map(_.nanoTime).getOrElse(System.nanoTime)
    val overMax = System.nanoTime > peekNano + maxDuration.toNanos
    if (to.size >= max || overMax) {
      func()
    } else if (from.peek() != null && to.size < max) {
      to.add(from.poll.copy(nanoTime = System.nanoTime))
      ()
    }
  }

  def bufferedKinesis(): Unit =
    buffer(
      kinesisQ,
      kinesisBuffer,
      20,
      10.second,
      kinesisFlow
    )

  def kinesisFlow(): Unit =
    if (kinesisBuffer.peek != null && System.nanoTime > kinesisBuffer.peek.lastRetryNanoTime + RetryPeriod.toNanos) {
      val retryToKinesis =
        (1 to kinesisBuffer.size).toList.map(_ => Option(kinesisBuffer.poll)).flatten
      if (retryToKinesis.nonEmpty) {
        println(s"Sending to Kinesis: $retryToKinesis")
        q.addAll(
          retryToKinesis
            .map(
              ev =>
                ev.copy(kinesisCounter = ev.kinesisCounter - 1, lastRetryNanoTime = System.nanoTime)
            )
            .asJava
        )
      }
      ()
    }

  def bufferedSqs() =
    buffer(
      sqsQ,
      sqsBuffer,
      10,
      10.second,
      sqsFlow
    )

  def sqsFlow(): Unit =
    if (sqsBuffer.peek != null && System.nanoTime > sqsBuffer.peek.lastRetryNanoTime + RetryPeriod.toNanos) {
      val toSqs = (1 to sqsBuffer.size).toList.map(_ => Option(sqsBuffer.poll)).flatten
      if (toSqs.nonEmpty) {
        println(s"Sending to SQS: $toSqs")
        q.addAll(
          toSqs
            .map(ev => ev.copy(sqsCounter = ev.sqsCounter - 1, lastRetryNanoTime = System.nanoTime))
            .asJava
        )
      }
      ()
    }

}
