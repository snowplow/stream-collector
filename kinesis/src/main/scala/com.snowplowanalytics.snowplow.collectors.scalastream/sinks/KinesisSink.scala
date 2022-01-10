/*
 * Copyright (c) 2013-2022 Snowplow Analytics Ltd. All rights reserved.
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
import java.util.concurrent.ScheduledExecutorService
import java.util.UUID
import com.amazonaws.auth._
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.kinesis.{AmazonKinesis, AmazonKinesisClientBuilder}
import com.amazonaws.services.kinesis.model._
import com.amazonaws.services.sqs.{AmazonSQS, AmazonSQSClientBuilder}
import com.amazonaws.services.sqs.model.{
  MessageAttributeValue,
  QueueDoesNotExistException,
  SendMessageBatchRequest,
  SendMessageBatchRequestEntry
}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContextExecutorService, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import cats.syntax.either._
import com.snowplowanalytics.snowplow.collectors.scalastream.model._
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.KinesisSink.SqsClientAndName

/**
  * Kinesis Sink for the Scala Stream Collector.
  */
class KinesisSink private (
  client: AmazonKinesis,
  kinesisConfig: Kinesis,
  bufferConfig: BufferConfig,
  streamName: String,
  executorService: ScheduledExecutorService,
  maybeSqs: Option[SqsClientAndName]
) extends Sink {
  import KinesisSink._

  maybeSqs match {
    case Some(sqs) =>
      log.info(
        s"SQS buffer for '$streamName' Kinesis sink is set up as: ${sqs.sqsBufferName}."
      )
    case None =>
      log.warn(
        s"No SQS buffer for surge protection set up (consider setting a SQS Buffer in config.hocon)."
      )
  }

  // Records must not exceed MaxBytes.
  // The limit is 1MB for Kinesis.
  // When SQS buffer is enabled MaxBytes has to be 256k,
  // but we encode the message with Base64 for SQS, so the limit drops to 192k.
  private val WithBuffer     = maybeSqs.isDefined
  private val SqsLimit       = 192000 // 256000 / 4 * 3
  private val KinesisLimit   = 1000000
  override val MaxBytes: Int = if (WithBuffer) SqsLimit else KinesisLimit

  private val ByteThreshold   = bufferConfig.byteLimit
  private val RecordThreshold = bufferConfig.recordLimit
  private val TimeThreshold   = bufferConfig.timeLimit

  private val maxBackoff      = kinesisConfig.backoffPolicy.maxBackoff
  private val minBackoff      = kinesisConfig.backoffPolicy.minBackoff
  private val randomGenerator = new java.util.Random()

  private val MaxSqsBatchSizeN = 10
  private val MaxRetries       = 3 // TODO: make this configurable

  implicit lazy val ec: ExecutionContextExecutorService =
    concurrent.ExecutionContext.fromExecutorService(executorService)

  // Is the collector detecting an outage downstream
  @volatile private var outage: Boolean = false
  override def isHealthy: Boolean       = !outage

  override def storeRawEvents(events: List[Array[Byte]], key: String): Unit =
    events.foreach(e => EventStorage.store(e, key))

  object EventStorage {
    private val storedEvents              = ListBuffer.empty[Events]
    private var byteCount                 = 0L
    @volatile private var lastFlushedTime = 0L

    def store(event: Array[Byte], key: String): Unit = {
      val eventBytes = ByteBuffer.wrap(event)
      val eventSize  = eventBytes.capacity

      synchronized {
        if (storedEvents.size + 1 > RecordThreshold || byteCount + eventSize > ByteThreshold) {
          flush()
        }
        storedEvents += Events(eventBytes.array(), key)
        byteCount += eventSize
      }
    }

    def flush(): Unit = {
      val eventsToSend = synchronized {
        val evts = storedEvents.result
        storedEvents.clear()
        byteCount = 0
        evts
      }
      lastFlushedTime = System.currentTimeMillis()
      sinkBatch(eventsToSend, KinesisStream(maybeSqs), minBackoff)(MaxRetries)
    }

    def getLastFlushTime: Long = lastFlushedTime

    /**
      * Recursively schedule a task to send everything in EventStorage.
      * Even if the incoming event flow dries up, all stored events will eventually get sent.
      * Whenever TimeThreshold milliseconds have passed since the last call to flush, call flush.
      * @param interval When to schedule the next flush
      */
    def scheduleFlush(interval: Long = TimeThreshold): Unit = {
      executorService.schedule(
        new Runnable {
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

  /**
    * The number of retries is unlimited, but targets switch every 10 attempts if an SQS buffer is configured.
    * @param batch A batch of events to be written
    * @param target A Kinesis stream or SQS queue to write events to
    * @param nextBackoff Period of time to wait before next attempt if retrying
    * @param retriesLeft How many retries are left to the current target. Once exhausted, the target will be switched.
    */
  def sinkBatch(batch: List[Events], target: Target, nextBackoff: Long)(
    retriesLeft: Int
  ): Unit =
    if (batch.nonEmpty) target match {
      case KinesisStream(buffer) =>
        log.info(s"Writing ${batch.size} Thrift records to Kinesis stream $streamName.")
        writeBatchToKinesis(batch).onComplete {
          case Success(s) =>
            // We only need to keep track of Kinesis health if no SQS buffer is configured.
            // We also need a way to recover from an SQS outage without writing to SQS,
            // as there's no guarantee the latter will happen.
            if (outage) {
              outage = buffer match {
                case None => false
                case Some(SqsClientAndName(sqsClient, bufferName)) =>
                  sqsQueueExists(sqsClient, bufferName) match {
                    case Success(true)  => false
                    case Success(false) => true
                    case Failure(_)     => true
                  }
              }
            }
            val results      = s.getRecords.asScala.toList
            val failurePairs = batch.zip(results).filter(_._2.getErrorMessage != null)
            log.info(
              s"Successfully wrote ${batch.size - failurePairs.size} out of ${batch.size} records to Kinesis stream $streamName."
            )
            if (failurePairs.nonEmpty) {
              failurePairs.foreach(f =>
                log.error(
                  s"Failed writing record to Kinesis stream $streamName, with error code [${f._2.getErrorCode}] and message [${f._2.getErrorMessage}]."
                )
              )
              val failures = failurePairs.map(_._1)
              val errorMessage = {
                if (buffer.isEmpty || retriesLeft > 1)
                  s"Retrying all records that could not be written to Kinesis stream $streamName in $nextBackoff milliseconds..."
                else
                  s"Sending ${failures.size} records that could not be written to Kinesis stream $streamName to SQS queue ${buffer.get.sqsBufferName} in $nextBackoff milliseconds..."
              }
              log.error(errorMessage)
              scheduleWrite(failures, target, nextBackoff)(retriesLeft - 1)
            }
          case Failure(f) =>
            log.error("Writing failed with error:", f)

            buffer match {
              case _ if retriesLeft > 1 =>
                log.error(s"Retrying writing batch to Kinesis stream $streamName in $nextBackoff milliseconds...")
              case None =>
                outage = true // No SQS buffer and we can't write to Kinesis => suspected outage
              case Some(_) =>
                log.error(s"Sending batch to SQS queue ${buffer.get.sqsBufferName} in $nextBackoff milliseconds...")
            }

            scheduleWrite(batch, target, nextBackoff)(retriesLeft - 1)
        }

      case SqsQueue(sqs) =>
        log.info(s"Writing ${batch.size} Thrift records to SQS queue ${sqs.sqsBufferName}.")
        writeBatchToSqs(batch, sqs).onComplete {
          case Success(s) =>
            outage = false
            log.info(
              s"Successfully wrote ${batch.size - s.size} out of ${batch.size} messages to SQS queue ${sqs.sqsBufferName}."
            )
            if (s.nonEmpty) {
              s.foreach { f =>
                log.error(
                  s"Failed writing message to SQS queue ${sqs.sqsBufferName}, with error code [${f._2.code}] and message [${f._2.message}]."
                )
              }
              val errorMessage = {
                if (retriesLeft > 1)
                  s"Retrying all messages that could not be written to SQS queue ${sqs.sqsBufferName} in $nextBackoff milliseconds..."
                else
                  s"Sending ${s.size} messages that could not be written to SQS queue ${sqs.sqsBufferName} to Kinesis stream $streamName in $nextBackoff milliseconds..."
              }
              log.error(errorMessage)
              scheduleWrite(s.map(_._1), target, nextBackoff)(retriesLeft - 1)
            }
          case Failure(f) =>
            log.error("Writing failed with error:", f)

            if (retriesLeft > 1) {
              log.error(s"Retrying writing batch to SQS queue ${sqs.sqsBufferName} in $nextBackoff milliseconds...")
            } else {
              outage = true
              log.error(s"Sending batch to Kinesis stream $streamName in $nextBackoff milliseconds...")
            }

            scheduleWrite(batch, target, nextBackoff)(retriesLeft - 1)
        }
    }

  def writeBatchToKinesis(batch: List[Events]): Future[PutRecordsResult] =
    Future {
      val putRecordsRequest = {
        val prr = new PutRecordsRequest()
        prr.setStreamName(streamName)
        val putRecordsRequestEntryList = batch.map { event =>
          val prre = new PutRecordsRequestEntry()
          prre.setPartitionKey(event.key)
          prre.setData(ByteBuffer.wrap(event.payloads))
          prre
        }
        prr.setRecords(putRecordsRequestEntryList.asJava)
        prr
      }
      client.putRecords(putRecordsRequest)
    }

  /**
    * @return Empty list if all events were successfully inserted;
    *         otherwise a non-empty list of Events to be retried and the reasons why they failed.
    */
  def writeBatchToSqs(batch: List[Events], sqs: SqsClientAndName): Future[List[(Events, BatchResultErrorInfo)]] =
    Future {
      val splitBatch = split(batch, getByteSize, MaxSqsBatchSizeN, SqsLimit)
      splitBatch.map(toSqsMessages).flatMap { msgGroup =>
        val entries = msgGroup.map(_._2)
        val batchRequest =
          new SendMessageBatchRequest().withQueueUrl(sqs.sqsBufferName).withEntries(entries.asJava)
        val response = sqs.sqsClient.sendMessageBatch(batchRequest)
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
    }

  def toSqsMessages(events: List[Events]): List[(Events, SendMessageBatchRequestEntry)] =
    events.map(e =>
      (
        e,
        new SendMessageBatchRequestEntry(UUID.randomUUID.toString, b64Encode(e.payloads)).withMessageAttributes(
          Map(
            "kinesisKey" ->
              new MessageAttributeValue().withDataType("String").withStringValue(e.key)
          ).asJava
        )
      )
    )

  def b64Encode(msg: Array[Byte]): String = {
    val buffer = java.util.Base64.getEncoder.encode(msg)
    new String(buffer)
  }

  def scheduleWrite(batch: List[Events], target: Target, lastBackoff: Long = minBackoff)(
    retriesLeft: Int
  ): Unit = {
    val nextBackoff = getNextBackoff(lastBackoff)
    executorService.schedule(
      new Runnable {
        override def run(): Unit =
          // If an SQS buffer is configured, keep switching targets every 10 retries.
          // If no SQS buffer is configured, continue retrying to Kinesis forever.
          if (retriesLeft > 0) sinkBatch(batch, target, nextBackoff)(retriesLeft)
          else sinkBatch(batch, switchTarget(target), nextBackoff)(MaxRetries)
      },
      lastBackoff,
      MILLISECONDS
    )
    ()
  }

  // No-op if no SQS buffer is configured
  def switchTarget(target: Target): Target = target match {
    case KinesisStream(Some(sqs)) => SqsQueue(sqs)
    case KinesisStream(None)      => KinesisStream(None)
    case SqsQueue(sqs)            => KinesisStream(Some(sqs))
  }

  /**
    * How long to wait before sending the next request
    * @param lastBackoff The previous backoff time
    * @return Maximum of two-thirds of lastBackoff and a random number between minBackoff and maxBackoff
    */
  private def getNextBackoff(lastBackoff: Long): Long = {
    val diff = (maxBackoff - minBackoff + 1).toInt
    (minBackoff + randomGenerator.nextInt(diff)).max(lastBackoff / 3 * 2)
  }

  def shutdown(): Unit = {
    EventStorage.flush()
    executorService.shutdown()
    executorService.awaitTermination(10000, MILLISECONDS)
    ()
  }
}

/** KinesisSink companion object with factory method */
object KinesisSink {

  sealed trait Target
  final case class KinesisStream(buffer: Option[SqsClientAndName]) extends Target
  final case class SqsQueue(sqs: SqsClientAndName) extends Target

  /**
    * Events to be written to Kinesis or SQS.
    * @param payloads Serialized events extracted from a CollectorPayload.
    *                 The size of this collection is limited by MaxBytes.
    *                 Not to be confused with a 'batch' events to sink.
    * @param key Partition key for Kinesis
    */
  final case class Events(payloads: Array[Byte], key: String)

  // Details about why messages failed to be written to SQS.
  final case class BatchResultErrorInfo(code: String, message: String)

  final case class SqsClientAndName(sqsClient: AmazonSQS, sqsBufferName: String)

  /**
    * Create a KinesisSink and schedule a task to flush its EventStorage.
    * Exists so that no threads can get a reference to the KinesisSink
    * during its construction.
    */
  def createAndInitialize(
    kinesisConfig: Kinesis,
    bufferConfig: BufferConfig,
    streamName: String,
    sqsBufferName: Option[String],
    enableStartupChecks: Boolean,
    executorService: ScheduledExecutorService
  ): Either[Throwable, KinesisSink] = {
    val clients = for {
      provider         <- getProvider(kinesisConfig.aws)
      kinesisClient    <- createKinesisClient(provider, kinesisConfig.endpoint, kinesisConfig.region)
      sqsClientAndName <- sqsBuffer(sqsBufferName, provider, kinesisConfig.region)
      _ = if (enableStartupChecks) runChecks(kinesisClient, streamName, sqsClientAndName)
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
        ks.EventStorage.scheduleFlush()
        ks
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
  ): Either[Throwable, AmazonKinesis] =
    Either.catchNonFatal(
      AmazonKinesisClientBuilder
        .standard()
        .withCredentials(provider)
        .withEndpointConfiguration(new EndpointConfiguration(endpoint, region))
        .build()
    )

  private def sqsBuffer(
    sqsBufferName: Option[String],
    provider: AWSCredentialsProvider,
    region: String
  ): Either[Throwable, Option[SqsClientAndName]] =
    sqsBufferName match {
      case Some(name) =>
        createSqsClient(provider, region).map(amazonSqs => Some(SqsClientAndName(amazonSqs, name)))
      case None => None.asRight
    }

  private def createSqsClient(provider: AWSCredentialsProvider, region: String): Either[Throwable, AmazonSQS] =
    Either.catchNonFatal(
      AmazonSQSClientBuilder.standard().withRegion(region).withCredentials(provider).build
    )

  private def runChecks(
    kinesisClient: AmazonKinesis,
    streamName: String,
    sqs: Option[SqsClientAndName]
  ): Unit = {
    lazy val log = LoggerFactory.getLogger(getClass)
    val kExists  = streamExists(kinesisClient, streamName)

    kExists match {
      case Success(true) =>
        ()
      case Success(false) =>
        log.error(s"Kinesis stream $streamName doesn't exist or isn't available.")
      case Failure(t) =>
        log.error(s"Error checking if stream $streamName exists", t)
    }

    sqs match {
      case Some(SqsClientAndName(sqsClient, bufferName)) =>
        sqsQueueExists(sqsClient, bufferName) match {
          case Success(true) =>
            ()
          case Success(false) =>
            log.error(s"SQS queue $bufferName is defined in config file, but does not exist or isn't available.")
          case Failure(t) =>
            log.error(s"Error checking if SQS queue $bufferName exists", t)
        }
      case None =>
        kExists match {
          case Success(true) => ()
          case _             => log.error(s"SQS buffer is not configured.")
        }
    }
  }

  /**
    * Check whether a Kinesis stream exists
    *
    * An error is expected if Kinesis is unavailable or if api usage quota is exceeded.
    *
    * @param name Name of the stream
    * @return Whether the stream exists
    */
  private def streamExists(client: AmazonKinesis, name: String): Try[Boolean] =
    Try {
      val describeStreamResult = client.describeStream(name)
      val status               = describeStreamResult.getStreamDescription.getStreamStatus
      status == "ACTIVE" || status == "UPDATING"
    }.recover {
      case _: ResourceNotFoundException => false
    }

  /**
    * Check whether an SQS queue (buffer) exists
    *
    * An error is expected if SQS is unavailable or if api usage quota is exceeded.
    *
    * @param name Name of the queue
    * @return Whether the queue exists
    */
  private def sqsQueueExists(client: AmazonSQS, name: String): Try[Boolean] =
    Try {
      client.getQueueUrl(name)
      true
    }.recover {
      case _: QueueDoesNotExistException => false
    }

  /**
    * Splits a Kinesis-sized batch of `Events` into smaller batches that meet the SQS limit.
    * @param batch A batch of up to `KinesisLimit` that must be split into smaller batches.
    * @param getByteSize How to get the size of a batch.
    * @param maxRecords Max records for the smaller batches.
    * @param maxBytes Max byte size for the smaller batches.
    * @return A batch of smaller batches, each one of which meets the limits.
    */
  def split(
    batch: List[Events],
    getByteSize: Events => Int,
    maxRecords: Int,
    maxBytes: Int
  ): List[List[Events]] = {
    var bytes = 0L
    @scala.annotation.tailrec
    def go(originalBatch: List[Events], tmpBatch: List[Events], newBatch: List[List[Events]]): List[List[Events]] =
      (originalBatch, tmpBatch) match {
        case (Nil, Nil) => newBatch
        case (Nil, acc) => acc :: newBatch
        case (h :: t, acc) if acc.size + 1 > maxRecords || getByteSize(h) + bytes > maxBytes =>
          bytes = getByteSize(h).toLong
          go(t, h :: Nil, acc :: newBatch)
        case (h :: t, acc) =>
          bytes += getByteSize(h)
          go(t, h :: acc, newBatch)
      }
    go(batch, Nil, Nil).map(_.reverse).reverse.filter(_.nonEmpty)
  }

  def getByteSize(events: Events): Int = ByteBuffer.wrap(events.payloads).capacity
}
