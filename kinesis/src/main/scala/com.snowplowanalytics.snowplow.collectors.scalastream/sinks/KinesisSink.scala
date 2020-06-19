/*
 * Copyright (c) 2013-2020 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.collectors.scalastream
package sinks

import java.nio.ByteBuffer
import scala.collection.concurrent.TrieMap
import java.util.concurrent.ScheduledExecutorService

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import cats.syntax.either._
import com.amazonaws.auth._
import com.amazonaws.services.kinesis.model._
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.kinesis.{AmazonKinesis, AmazonKinesisClientBuilder}
import com.amazonaws.services.sqs.{AmazonSQS, AmazonSQSClientBuilder}
import com.amazonaws.services.sqs.model.{
  MessageAttributeValue,
  SendMessageBatchRequest,
  SendMessageBatchRequestEntry
}
import java.util.UUID
import model._
import KinesisSink.SqsClientAndName

/** KinesisSink companion object with factory method */
object KinesisSink {

  case class SqsClientAndName(sqsClient: AmazonSQS, sqsBufferName: String)

  /**
   * Create a KinesisSink and schedule a task to flush its EventStorage
   * Exists so that no threads can get a reference to the KinesisSink
   * during its construction
   */
  def createAndInitialize(
    kinesisConfig: Kinesis,
    bufferConfig: BufferConfig,
    streamName: String,
    sqsBufferName: Option[String],
    executorService: ScheduledExecutorService
  ): Either[Throwable, KinesisSink] = {
    val clients = for {
      credentials <- getProvider(kinesisConfig.aws)
      kinesisClient = createKinesisClient(credentials, kinesisConfig.endpoint, kinesisConfig.region)
      _ <- if (streamExists(kinesisClient, streamName)) true.asRight
      else new IllegalArgumentException(s"Kinesis stream $streamName doesn't exist").asLeft
      sqsClientAndName <- sqsBuffer(sqsBufferName, credentials, kinesisConfig.region)
    } yield (kinesisClient, sqsClientAndName)

    clients.map {
      case (kinesisClient, sqsClientAndName) =>
        val ks =
          new KinesisSink(
            kinesisClient,
            kinesisConfig,
            bufferConfig,
            streamName,
            executorService,
            sqsClientAndName
          )
        ks.scheduleFlush()

        // When the application is shut down try to send all stored events
        Runtime.getRuntime.addShutdownHook(new Thread {
          override def run(): Unit = {
            ks.EventStorage.flush()
            ks.shutdown()
          }
        })
        ks
    }
  }

  /** Create an aws credentials provider through env variables and iam. */
  private def getProvider(awsConfig: AWSConfig): Either[Throwable, AWSCredentialsProvider] = {
    def isDefault(key: String): Boolean = key == "default"
    def isIam(key: String): Boolean = key == "iam"
    def isEnv(key: String): Boolean = key == "env"

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

  /**
   * Creates a new Kinesis client.
   * @param provider aws credentials provider
   * @param endpoint kinesis endpoint where the stream resides
   * @param region aws region where the stream resides
   * @return the initialized AmazonKinesisClient
   */
  private def createKinesisClient(
    provider: AWSCredentialsProvider,
    endpoint: String,
    region: String
  ): AmazonKinesis =
    AmazonKinesisClientBuilder
      .standard()
      .withCredentials(provider)
      .withEndpointConfiguration(new EndpointConfiguration(endpoint, region))
      .build()

  /**
   * Check whether a Kinesis stream exists
   *
   * @param name Name of the stream
   * @return Whether the stream exists
   */
  private def streamExists(client: AmazonKinesis, name: String): Boolean =
    try {
      val describeStreamResult = client.describeStream(name)
      val status = describeStreamResult.getStreamDescription.getStreamStatus
      status == "ACTIVE" || status == "UPDATING"
    } catch {
      case _: ResourceNotFoundException => false
    }

  private def createSqsClient(provider: AWSCredentialsProvider, region: String) =
    Either.catchNonFatal(
      AmazonSQSClientBuilder
        .standard()
        .withRegion(region)
        .withCredentials(provider)
        .build
    )

  def sqsBuffer(
    sqsBufferName: Option[String],
    provider: AWSCredentialsProvider,
    region: String
  ): Either[Throwable, Option[SqsClientAndName]] =
    sqsBufferName match {
      case Some(name) =>
        createSqsClient(provider, region)
          .map(amazonSqs => Some(SqsClientAndName(amazonSqs, name)))
      case None => None.asRight
    }

}

/**
 * Kinesis Sink for the Scala collector.
 */
class KinesisSink private (
  client: AmazonKinesis,
  kinesisConfig: Kinesis,
  bufferConfig: BufferConfig,
  streamName: String,
  executorService: ScheduledExecutorService,
  maybeSqs: Option[SqsClientAndName]
) extends Sink {
  // Records must not exceed MaxBytes - 1MB
  val MaxBytes = 1000000
  val BackoffTime = 3000L

  val ByteThreshold = bufferConfig.byteLimit
  val RecordThreshold = bufferConfig.recordLimit
  val TimeThreshold = bufferConfig.timeLimit

  private val maxBackoff = kinesisConfig.backoffPolicy.maxBackoff
  private val minBackoff = kinesisConfig.backoffPolicy.minBackoff
  private val randomGenerator = new java.util.Random()

  log.info("Creating thread pool of size " + kinesisConfig.threadPoolSize)
  maybeSqs match {
    case Some(sqs) =>
      log.info(
        s"SQS buffer for failed Kinesis stream '$streamName' is set up as: ${sqs.sqsBufferName}."
      )
    case None =>
      log.warn(
        s"No SQS buffer for surge protection set up (consider setting a SQS Buffer in config.hocon)."
      )
  }

  implicit lazy val ec = concurrent.ExecutionContext.fromExecutorService(executorService)

  /**
   * Recursively schedule a task to send everthing in EventStorage
   * Even if the incoming event flow dries up, all stored events will eventually get sent
   * Whenever TimeThreshold milliseconds have passed since the last call to flush, call flush.
   * @param interval When to schedule the next flush
   */
  def scheduleFlush(interval: Long = TimeThreshold): Unit = {
    executorService.schedule(
      new Thread {
        override def run(): Unit = {
          val lastFlushed = EventStorage.getLastFlushTime()
          val currentTime = System.currentTimeMillis()
          if (currentTime - lastFlushed >= TimeThreshold) {
            EventStorage.flush()
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

  case class Event(msg: ByteBuffer, key: String)
  case class EventWithId(event: Event, id: UUID)

  object EventStorage {
    private var storedEvents = List.empty[Event]
    private var byteCount = 0L
    @volatile private var lastFlushedTime = 0L

    def store(event: Array[Byte], key: String): Unit = {
      val eventBytes = ByteBuffer.wrap(event)
      val eventSize = eventBytes.capacity
      if (eventSize >= MaxBytes) {
        log.error(
          s"Record of size $eventSize bytes is too large - must be less than $MaxBytes bytes"
        )
      } else {
        synchronized {
          storedEvents = Event(eventBytes, key) :: storedEvents
          byteCount += eventSize
          if (storedEvents.size >= RecordThreshold || byteCount >= ByteThreshold) {
            flush()
          }
        }
      }
    }

    def flush(): Unit = {
      val eventsToSend = synchronized {
        val evts = storedEvents.reverse
        storedEvents = Nil
        byteCount = 0
        evts
      }
      lastFlushedTime = System.currentTimeMillis()
      sendBatch(eventsToSend)
    }

    def getLastFlushTime(): Long = lastFlushedTime
  }

  def storeRawEvents(events: List[Array[Byte]], key: String): List[Array[Byte]] = {
    events.foreach(e => EventStorage.store(e, key))
    Nil
  }

  def scheduleBatch(batch: List[Event], lastBackoff: Long = minBackoff): Unit = {
    val nextBackoff = getNextBackoff(lastBackoff)
    executorService.schedule(new Thread {
      override def run(): Unit =
        sendBatch(batch, nextBackoff)
    }, lastBackoff, MILLISECONDS)
    ()
  }

  /**
   *  Max number of retries is unlimitted, so when Kinesis stream is under heavy load,
   *  the events accumulate in collector memory for later retries. The fix for this is to use
   *  sqs queue as a buffer and sqs2kinesis to move events back from sqs queue to kinesis stream.
   *  Consider using sqs buffer in heavy load scenarios.
   *
   */
  def sendBatch(batch: List[Event], nextBackoff: Long = minBackoff): Unit =
    if (batch.nonEmpty) {
      log.info(s"Writing ${batch.size} Thrift records to Kinesis stream ${streamName}")

      multiPut(streamName, batch).onComplete {
        case Success(s) => {
          val results = s.getRecords.asScala.toList
          val failurePairs = batch zip results filter { _._2.getErrorMessage != null }
          log.info(
            s"Successfully wrote ${batch.size - failurePairs.size} out of ${batch.size} records"
          )
          if (failurePairs.nonEmpty) {
            failurePairs.foreach(
              f =>
                log.error(
                  s"Record failed with error code [${f._2.getErrorCode}] and message [${f._2.getErrorMessage}]"
                )
            )
            val failures = failurePairs.map(_._1)
            val retryErrorMsg = s"Retrying all failed records in $nextBackoff milliseconds..."
            sendToSqsOrRetryToKinesis(failures, nextBackoff)(retryErrorMsg)
          }
        }
        case Failure(f) => {
          log.error("Writing failed.", f)
          val retryErrorMsg = s"Retrying in $nextBackoff milliseconds..."
          sendToSqsOrRetryToKinesis(batch, nextBackoff)(retryErrorMsg)
        }
      }
    }

  private def sendToSqsOrRetryToKinesis(
    failures: List[Event],
    nextBackoff: Long
  )(
    retryErrorMsg: String
  ): Unit =
    maybeSqs match {
      case Some(sqs) =>
        log.info(
          s"Sending ${failures.size} events from a batch to SQS buffer queue: ${sqs.sqsBufferName}"
        )
        putToSqs(sqs, failures)
        ()
      case None =>
        log.error(retryErrorMsg)
        log.warn(
          s"${failures.size} failed events scheduled for retry (consider setting a SQS Buffer in config.hocon)"
        )
        scheduleBatch(failures, nextBackoff)
    }

  private def putToSqs(sqs: SqsClientAndName, batch: List[Event]): Future[Unit] =
    Future {
      log.info(s"Writing ${batch.size} messages to SQS queue: ${sqs.sqsBufferName}")
      val sqsBatchEntries = batch.map(addUuids).map(toSqsBatchEntry)
      val MaxSqsBatchSize = 10
      sqsBatchEntries.grouped(MaxSqsBatchSize).foreach { batchEntryGroup =>
        sendToSqsWithRetry(sqs, batchEntryGroup)
      }
    }

  /**
   *  UUIDs are generated for retry purpose. When batch fails, we want to retry (limited number of times)
   *   only those entries that failed within the batch.
   */
  private val addUuids: (Event) => EventWithId =
    event => EventWithId(event, java.util.UUID.randomUUID())

  private val toSqsBatchEntry: (EventWithId) => (UUID, SendMessageBatchRequestEntry) = {
    eventWithId =>
      val b64EncodedMsg = encode(eventWithId.event.msg)
      val batchReqEntry = new SendMessageBatchRequestEntry(UUID.randomUUID.toString, b64EncodedMsg)
        .withMessageAttributes(
          Map(
            "kinesisKey" ->
              new MessageAttributeValue()
                .withDataType("String")
                .withStringValue(eventWithId.event.key)
          ).asJava
        )
        .withId(eventWithId.id.toString)
      (eventWithId.id, batchReqEntry)
  }

  private def createBatchRequest(queueUrl: String, batch: List[SendMessageBatchRequestEntry]) =
    new SendMessageBatchRequest()
      .withQueueUrl(queueUrl)
      .withEntries(batch.asJava)

  private def sendToSqsWithRetry(
    sqs: SqsClientAndName,
    batchEntryGroup: List[(UUID, SendMessageBatchRequestEntry)]
  ) = {
    val current = TrieMap(batchEntryGroup: _*)
    retry
      .JitterBackoff(10, 1.second)
      .apply { () =>
        Future {
          val batchRequest = createBatchRequest(sqs.sqsBufferName, current.values.toList)
          val res = sqs.sqsClient.sendMessageBatch(batchRequest)
          val failed = res.getFailed().asScala
          if (failed.nonEmpty) {
            val errors = failed.map(_.toString).mkString(", ")
            log.error(s"Sending to SQS queue [${sqs.sqsBufferName}] failed with errors [$errors]")
            val successIds =
              res.getSuccessful().asScala.map(_.getId()).map(UUID.fromString).toList
            log.info(
              s"${successIds.size} out of ${current.size} from batch was successfully send to SQS queue: ${sqs.sqsBufferName}"
            )
            successIds.foreach(current.remove)
            None // means: retry
          } else {
            log.info(
              s"Batch of ${current.size} was successfully send to SQS queue: ${sqs.sqsBufferName}."
            )
            Some(res.getSuccessful.size) //means: do not retry (the number doesn't matter in this context)
          }
        }
      }
  }

  private def encode(bufMsg: ByteBuffer): String = {
    val buffer = java.util.Base64.getEncoder.encode(bufMsg)
    new String(buffer.array())
  }

  private def multiPut(name: String, batch: List[Event]): Future[PutRecordsResult] =
    Future {
      val putRecordsRequest = {
        val prr = new PutRecordsRequest()
        prr.setStreamName(name)
        val putRecordsRequestEntryList = batch.map { event =>
          val prre = new PutRecordsRequestEntry()
          prre.setPartitionKey(event.key)
          prre.setData(event.msg)
          prre
        }
        prr.setRecords(putRecordsRequestEntryList.asJava)
        prr
      }
      client.putRecords(putRecordsRequest)
    }

  /**
   * How long to wait before sending the next request
   * @param lastBackoff The previous backoff time
   * @return Minimum of maxBackoff and a random number between minBackoff and three times lastBackoff
   */
  private def getNextBackoff(lastBackoff: Long): Long =
    (minBackoff + randomGenerator.nextDouble() * (lastBackoff * 3 - minBackoff)).toLong
      .min(maxBackoff)

  def shutdown(): Unit = {
    executorService.shutdown()
    executorService.awaitTermination(10000, MILLISECONDS)
    ()
  }
}
