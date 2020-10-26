package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import scala.collection.JavaConverters._
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import scala.concurrent.duration._
import scala.util.control.NonFatal

object Bar extends App {

  case class IntWithCounter(x: Int, counter: Int)

  val q = new ConcurrentLinkedQueue[IntWithCounter]
  val executorService = new ScheduledThreadPoolExecutor(4)

  q.addAll((1 to 100).toList.map(x => (IntWithCounter(x, 3))).asJava)
  println(s"q: $q")

  scala.sys.addShutdownHook {
    println(s"shutting down with shutdown hook")
    println(s"size of queue: ${q.size}")
  }

  executorService.scheduleAtFixedRate(
    () => logNonFatalAndKeepRunning(),
    100,
    10,
    MILLISECONDS
  )

  def logNonFatalAndKeepRunning() =
    try {
      bar()
    } catch {
      case NonFatal(e) =>
        println(s"ERROR: ${e.getMessage}")
    }

  def bar() = {
    val xs = (1 to 20).toList.map(_ => Option(q.poll)).flatten
    val (retryToKinesis, toSqs) = xs.partition(_.counter > 0)

    if (retryToKinesis.nonEmpty) {
      println(s"Sending to Kinesis: $retryToKinesis")
      q.addAll(retryToKinesis.map(ev => ev.copy(counter = ev.counter - 1)).asJava)
    }

    if (toSqs.nonEmpty) println(s"Sending to SQS: $toSqs")

  }

}
