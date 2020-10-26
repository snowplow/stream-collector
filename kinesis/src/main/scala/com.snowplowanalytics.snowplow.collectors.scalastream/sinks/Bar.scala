package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import scala.collection.JavaConverters._
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import scala.concurrent.duration._
import scala.util.control.NonFatal

object Bar extends App {

  case class IntWithCounter(
    x: Int,
    counter: Int,
    nanoTime: Long
  )

  val q = new ConcurrentLinkedQueue[IntWithCounter]
  val executorService = new ScheduledThreadPoolExecutor(4)

  val kinesisQ = new ConcurrentLinkedQueue[IntWithCounter]
  val sqsQ = new ConcurrentLinkedQueue[IntWithCounter]

  val kinesisBuffer = new ConcurrentLinkedQueue[IntWithCounter]
  val sqsBuffer = new ConcurrentLinkedQueue[IntWithCounter]

  q.addAll((1 to 100).toList.map(x => (IntWithCounter(x, 3, 0L))).asJava)

  scala.sys.addShutdownHook {
    println(s"shutting down with shutdown hook")
    println(s"size of queue: ${q.size}")
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
      if (head.counter > 0) kinesisQ.add(head) else sqsQ.add(head)
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

  def kinesisFlow(): Unit = {
    val retryToKinesis =
      (1 to kinesisBuffer.size).toList.map(_ => Option(kinesisBuffer.poll)).flatten
    if (retryToKinesis.nonEmpty) {
      println(s"Sending to Kinesis: $retryToKinesis")
      q.addAll(retryToKinesis.map(ev => ev.copy(counter = ev.counter - 1)).asJava)
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

  def sqsFlow(): Unit = {
    val toSqs = (1 to sqsBuffer.size).toList.map(_ => Option(sqsBuffer.poll)).flatten
    if (toSqs.nonEmpty) println(s"Sending to SQS: $toSqs")
  }

}
