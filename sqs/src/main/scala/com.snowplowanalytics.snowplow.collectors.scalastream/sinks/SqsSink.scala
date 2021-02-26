/*
 * Copyright (c) 2013-2021 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService

import com.amazonaws.auth.{
  AWSCredentialsProvider,
  AWSStaticCredentialsProvider,
  BasicAWSCredentials,
  DefaultAWSCredentialsProviderChain,
  EnvironmentVariableCredentialsProvider,
  InstanceProfileCredentialsProvider
}
import com.amazonaws.services.sqs.{AmazonSQS, AmazonSQSClientBuilder}
import com.amazonaws.services.sqs.model.{
  QueueDoesNotExistException,
  SendMessageBatchRequest,
  SendMessageBatchRequestEntry
}
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContextExecutorService, Future}
import scala.collection.JavaConverters._
import scala.concurrent.duration.MILLISECONDS
import scala.util.{Failure, Random, Success}

import cats.syntax.either._

import com.snowplowanalytics.snowplow.collectors.scalastream.model._

/**
  *  SQS sink for the Scala Stream Collector
  */
class SqsSink private (
  client: AmazonSQS,
  sqsConfig: Sqs,
  bufferConfig: BufferConfig,
  queueName: String,
  executorService: ScheduledExecutorService
) extends Sink {
  // Records must not exceed 256K when writing to SQS.
  // Since messages can be base64-encoded, they must meet that limit after encoding.
  override val MaxBytes = 192000 // 256000 / 4 * 3

  private val ByteThreshold: Long   = bufferConfig.byteLimit
  private val RecordThreshold: Long = bufferConfig.recordLimit
  private val TimeThreshold: Long   = bufferConfig.timeLimit

  private val maxBackoff: Long        = sqsConfig.backoffPolicy.maxBackoff
  private val minBackoff: Long        = sqsConfig.backoffPolicy.minBackoff
  private val randomGenerator: Random = new java.util.Random()

  implicit lazy val ec: ExecutionContextExecutorService =
    concurrent.ExecutionContext.fromExecutorService(executorService)

  def storeRawEvents(events: List[Array[Byte]], key: String): List[Array[Byte]] = {
    events.foreach(e => EventBuffer.store(e))
    Nil
  }

  object EventBuffer {
    type Event = Array[Byte]

    private var storedEvents              = List.empty[ByteBuffer]
    private var byteCount                 = 0L
    @volatile private var lastFlushedTime = 0L

    def store(event: Event): Unit = {
      val eventBytes = ByteBuffer.wrap(event)
      val eventSize  = eventBytes.capacity
      if (eventSize >= MaxBytes) {
        log.error(
          s"Message of size $eventSize bytes is too large. It must be less than $MaxBytes bytes."
        )
      } else {
        synchronized {
          if (storedEvents.size + 1 > RecordThreshold || byteCount + eventSize > ByteThreshold) {
            flush()
          }
          storedEvents = eventBytes :: storedEvents
          byteCount += eventSize
        }
      }
    }

    def flush(): Unit = {
      val eventsToSend = synchronized {
        val evts = storedEvents.reverse
        storedEvents = Nil
        byteCount    = 0
        evts.map(_.array())
      }
      lastFlushedTime = System.currentTimeMillis()
      sendBatch(eventsToSend)
    }

    def getLastFlushTime: Long = lastFlushedTime

    /**
      * Recursively schedule a task to flush the EventBuffer.
      * Even if the incoming event flow dries up, all buffered events will eventually get sent.
      * Whenever TimeThreshold milliseconds have passed since the last call to flush, call flush.
      * @param interval When to schedule the next flush
      */
    def scheduleFlush(interval: Long = TimeThreshold): Unit = {
      executorService.schedule(
        new Thread {
          override def run(): Unit = {
            val lastFlushed = getLastFlushTime
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFlushed >= TimeThreshold) {
              flush()
              scheduleFlush(TimeThreshold)
            } else {
              scheduleFlush(TimeThreshold + lastFlushed - currentTime)
            }
          }
        },
        interval,
        MILLISECONDS
      )
      ()
    }
  }

  def sendBatch(batch: List[EventBuffer.Event], nextBackoff: Long = minBackoff): Unit =
    if (batch.nonEmpty) {
      log.info(s"Writing ${batch.size} Thrift records to SQS queue ${queueName}")

      multiPut(batch).onComplete {
        case Success(s) =>
          log.info(s"Successfully wrote ${batch.size - s.size} out of ${batch.size} messages.")
          if (s.nonEmpty) {
            s.foreach { f =>
              log.error(
                s"Message failed with error code [${f._2.code}] and message [${f._2.message}]"
              )
            }
            log.error(s"Retrying in $nextBackoff milliseconds...")
            scheduleBatch(s.map(_._1), nextBackoff)
          }
        case Failure(f) =>
          log.error("Writing failed.", f)
          log.error(s"Retrying in $nextBackoff milliseconds...")
          scheduleBatch(batch, nextBackoff)
      }
    }

  /** @return Empty list in case of success, non-empty list of Events to be retried and the reasons why they failed to be inserted in case of problem. */
  def multiPut(batch: List[EventBuffer.Event]): Future[List[(EventBuffer.Event, BatchResultErrorInfo)]] =
    Future {
      val MaxSqsBatchSize = 10 // SQS limit
      batch
        .map { e =>
          (e, toSqsMessage(e))
        }
        .grouped(MaxSqsBatchSize)
        .flatMap { msgGroup =>
          val entries = msgGroup.map(_._2)
          val batchRequest =
            new SendMessageBatchRequest().withQueueUrl(queueName).withEntries(entries.asJava)
          val response = client.sendMessageBatch(batchRequest)
          val failures = response
            .getFailed
            .asScala
            .toList
            .map { bree =>
              (bree.getId, BatchResultErrorInfo(bree.getCode, bree.getMessage))
            }
            .toMap
          // Events to retry and reasons for failure
          msgGroup.collect {
            case (e, m) if failures.contains(m.getId) =>
              (e, failures(m.getId))
          }
        }
        .toList
    }

  def toSqsMessage(event: EventBuffer.Event): SendMessageBatchRequestEntry =
    new SendMessageBatchRequestEntry(UUID.randomUUID.toString, b64Encode(event))

  def b64Encode(e: EventBuffer.Event): String = {
    val buffer = java.util.Base64.getEncoder.encode(e)
    new String(buffer)
  }

  def scheduleBatch(batch: List[EventBuffer.Event], lastBackoff: Long = minBackoff): Unit = {
    val nextBackoff = getNextBackoff(lastBackoff)
    executorService.schedule(new Thread {
      override def run(): Unit =
        sendBatch(batch, nextBackoff)
    }, lastBackoff, MILLISECONDS)
    ()
  }

  /**
    * How long to wait before sending the next request
    * @param lastBackoff The previous backoff time
    * @return Minimum of maxBackoff and a random number between minBackoff and three times lastBackoff
    */
  private def getNextBackoff(lastBackoff: Long): Long =
    (minBackoff + randomGenerator.nextDouble() * (lastBackoff * 3 - minBackoff)).toLong.min(maxBackoff)

  def shutdown(): Unit = {
    executorService.shutdown()
    executorService.awaitTermination(10000, MILLISECONDS)
    ()
  }

  case class BatchResultErrorInfo private (code: String, message: String)
}

/**
  *  SqsSink companion object with factory method
  */
object SqsSink {

  /**
    * Create an SqsSink and schedule a task to flush its EventBuffer.
    * Exists so that no threads can get a reference to the SqsSink
    * during its construction.
    */
  def createAndInitialize(
    sqsConfig: Sqs,
    bufferConfig: BufferConfig,
    queueName: String,
    executorService: ScheduledExecutorService
  ): Either[Throwable, SqsSink] = {
    val client = for {
      provider <- getProvider(sqsConfig.aws)
      client   <- createSqsClient(provider, sqsConfig.region)
      _ = sqsQueueExists(client, queueName)
    } yield client

    client.map { c =>
      val sqsSink = new SqsSink(c, sqsConfig, bufferConfig, queueName, executorService)
      sqsSink.EventBuffer.scheduleFlush()

      // When the application is shut down try to send all buffered events.
      Runtime
        .getRuntime
        .addShutdownHook(new Thread {
          override def run(): Unit = {
            sqsSink.EventBuffer.flush()
            sqsSink.shutdown()
          }
        })
      sqsSink
    }
  }

  private def createSqsClient(provider: AWSCredentialsProvider, region: String) =
    Either.catchNonFatal(
      AmazonSQSClientBuilder.standard().withRegion(region).withCredentials(provider).build
    )

  /**
    * Check whether an SQS queue exists
    * @param name Name of the queue
    * @return Whether the queue exists
    */
  private def sqsQueueExists(client: AmazonSQS, name: String): Unit = {
    lazy val log = LoggerFactory.getLogger(getClass)
    try {
      client.getQueueUrl(name)
      ()
    } catch {
      case _: QueueDoesNotExistException => log.error(s"SQS queue $name does not exist.")
      case t: Throwable                  => log.error(t.getMessage)
    }
  }

  /** Create an aws credentials provider through env variables and iam. */
  private def getProvider(awsConfig: AWSConfig): Either[Throwable, AWSCredentialsProvider] = {
    def isDefault(key: String): Boolean = key == "default"
    def isIam(key: String): Boolean     = key == "iam"
    def isEnv(key: String): Boolean     = key == "env"

    ((awsConfig.accessKey, awsConfig.secretKey) match {
      case (a, s) if isDefault(a) && isDefault(s) =>
        new DefaultAWSCredentialsProviderChain().asRight
      case (a, s) if isDefault(a) || isDefault(s) =>
        "accessKey and secretKey must both be set to 'default' or neither".asLeft
      case (a, s) if isIam(a) && isIam(s) =>
        InstanceProfileCredentialsProvider.getInstance().asRight
      case (a, s) if isIam(a) && isIam(s) =>
        "accessKey and secretKey must both be set to 'iam' or neither".asLeft
      case (a, s) if isEnv(a) && isEnv(s) =>
        new EnvironmentVariableCredentialsProvider().asRight
      case (a, s) if isEnv(a) || isEnv(s) =>
        "accessKey and secretKey must both be set to 'env' or neither".asLeft
      case _ =>
        new AWSStaticCredentialsProvider(
          new BasicAWSCredentials(awsConfig.accessKey, awsConfig.secretKey)
        ).asRight
    }).leftMap(new IllegalArgumentException(_))
  }
}
