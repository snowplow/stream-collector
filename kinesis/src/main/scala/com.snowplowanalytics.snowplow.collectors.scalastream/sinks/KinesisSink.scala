/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This software is made available by Snowplow Analytics, Ltd.,
  * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
  * located at https://docs.snowplow.io/limited-use-license-1.1
  * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
  * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
  */
package com.snowplowanalytics.snowplow.collectors.scalastream
package sinks

import cats.implicits._
import cats.effect.implicits._
import cats.effect.{Async, Resource, Sync}
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kinesis.KinesisClient
import software.amazon.awssdk.services.kinesis.model._
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model._
import com.snowplowanalytics.snowplow.collector.core.{Config, Sink}
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.KinesisSink._
import org.slf4j.LoggerFactory

import java.util.UUID
import java.util.concurrent.ExecutorService
import java.net.URI
import scala.jdk.CollectionConverters._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContextExecutorService
import scala.util.{Failure, Success, Try}

class KinesisSink[F[_]: Async] private (
  val maxBytes: Int,
  client: KinesisClient,
  kinesisConfig: KinesisSinkConfig,
  streamName: String,
  executorService: ExecutorService,
  maybeSqs: Option[Sqs]
) extends Sink[F] {

  private lazy val log = LoggerFactory.getLogger(getClass)

  maybeSqs match {
    case Some(sqs) =>
      log.info(s"SQS buffer for Kinesis stream $streamName is defined with name ${sqs.bufferName}")
    case None =>
      log.warn(
        s"No SQS buffer for surge protection set up for stream $streamName (consider setting it via the config file)"
      )
  }

  private val maxBackoff      = kinesisConfig.backoffPolicy.maxBackoff
  private val minBackoff      = kinesisConfig.backoffPolicy.minBackoff
  private val maxRetries      = kinesisConfig.backoffPolicy.maxRetries
  private val randomGenerator = new java.util.Random()

  private val MaxSqsBatchSizeN = 10

  private lazy val ec: ExecutionContextExecutorService =
    concurrent.ExecutionContext.fromExecutorService(executorService)

  @volatile private var kinesisHealthy: Boolean = false
  @volatile private var sqsHealthy: Boolean     = false
  override def isHealthy: F[Boolean]            = Sync[F].delay(kinesisHealthy || sqsHealthy)

  override def storeRawEvents(events: List[Array[Byte]]): F[Unit] =
    events
      .traverse { bytes =>
        Sync[F].delay(UUID.randomUUID).map(uuid => Events(bytes, uuid.toString))
      }
      .flatMap(sinkBatch(_))
      .void

  def sinkBatch(batch: List[Events]): F[Unit] =
    if (batch.nonEmpty) maybeSqs match {
      // Kinesis healthy
      case _ if kinesisHealthy =>
        writeBatchToKinesisWithRetries(batch, minBackoff, maxRetries)
      // No SQS buffer
      case None =>
        writeBatchToKinesisWithRetries(batch, minBackoff, maxRetries)
      // Kinesis not healthy and SQS buffer defined
      case Some(sqs) =>
        val (big, small) = batch.partition(_.payloads.size > sqs.maxBytes)
        val sqsAttempt =
          if (small.nonEmpty) writeBatchToSqsWithRetries(small, sqs, minBackoff, maxRetries) else Sync[F].unit
        val kinesisAttempt =
          if (big.nonEmpty) writeBatchToKinesisWithRetries(big, minBackoff, Int.MaxValue) else Sync[F].unit
        (sqsAttempt, kinesisAttempt).parTupled.void
    }
    else Sync[F].unit

  private def writeBatchToKinesisWithRetries(
    batch: List[Events],
    nextBackoff: Long,
    retriesLeft: Int
  ): F[Unit] = {
    log.info(s"Writing ${batch.size} records to Kinesis stream $streamName")
    writeBatchToKinesis(batch).attempt.flatMap {
      case Right(s) =>
        kinesisHealthy = true
        val results      = s.records().asScala.toList
        val failurePairs = batch.zip(results).filter(_._2.errorMessage() != null)
        log.info(
          s"Successfully wrote ${batch.size - failurePairs.size} out of ${batch.size} records to Kinesis stream $streamName"
        )
        if (failurePairs.nonEmpty) {
          failurePairs.groupBy(_._2.errorCode()).foreach {
            case (errorCode, items) =>
              val exampleMsg = items.map(_._2.errorMessage()).find(_.nonEmpty).getOrElse("")
              log.error(
                s"Writing ${items.size} records (out of ${batch.size}) to Kinesis stream $streamName failed with error code [$errorCode] and example message: $exampleMsg"
              )
          }
          val failedRecords = failurePairs.map(_._1)
          handleKinesisError(failedRecords, nextBackoff, retriesLeft)
        } else {
          Sync[F].unit
        }
      case Left(f) =>
        log.error(s"Writing ${batch.size} records to Kinesis stream $streamName failed with error: ${f.getMessage()}")
        handleKinesisError(batch, nextBackoff, retriesLeft)
    }
  }

  private def writeBatchToSqsWithRetries(
    batch: List[Events],
    sqs: Sqs,
    nextBackoff: Long,
    retriesLeft: Int
  ): F[Unit] = {
    log.info(s"Writing ${batch.size} records to SQS buffer ${sqs.bufferName}")
    writeBatchToSqs(batch, sqs).attempt.flatMap {
      case Right(s) =>
        sqsHealthy = true
        log.info(
          s"Successfully wrote ${batch.size - s.size} out of ${batch.size} records to SQS buffer ${sqs.bufferName}"
        )
        if (s.nonEmpty) {
          s.groupBy(_._2.code).foreach {
            case (errorCode, items) =>
              val exampleMsg = items.map(_._2.message).find(_.nonEmpty).getOrElse("")
              log.error(
                s"Writing ${items.size} records (out of ${batch.size}) to SQS buffer ${sqs.bufferName} failed with error code [$errorCode] and example message: $exampleMsg"
              )
          }
          val failedRecords = s.map(_._1)
          handleSqsError(failedRecords, sqs, nextBackoff, retriesLeft)
        } else Sync[F].unit
      case Left(f) =>
        log.error(
          s"Writing ${batch.size} records to SQS buffer ${sqs.bufferName} failed with error: ${f.getMessage()}"
        )
        handleSqsError(batch, sqs, nextBackoff, retriesLeft)
    }
  }

  private def handleKinesisError(failedRecords: List[Events], nextBackoff: Long, retriesLeft: Int): F[Unit] =
    if (retriesLeft > 0) {
      log.error(
        s"Retrying to write ${failedRecords.size} records to Kinesis stream $streamName in $nextBackoff milliseconds. $retriesLeft retries left"
      )
      val nextNextBackoff = getNextBackoff(nextBackoff)
      Async[F].sleep(nextBackoff.millis) >> writeBatchToKinesisWithRetries(
        failedRecords,
        nextNextBackoff,
        retriesLeft - 1
      )
    } else {
      val error = s"Maximum number of retries reached for Kinesis stream $streamName for ${failedRecords.size} records"
      maybeSqs match {
        case Some(sqs) =>
          log.error(
            s"$error. SQS buffer ${sqs.bufferName} defined. Retrying to send the events to SQS"
          )
          // If Kinesis was already unhealthy, the background check is already running.
          // It can happen when the collector switches back and forth between Kinesis and SQS.
          if (kinesisHealthy) {
            this.synchronized {
              if (kinesisHealthy) {
                kinesisHealthy = false
                checkKinesisHealth()
              }
            }
          }
          val (big, small) = failedRecords.partition(_.payloads.size > sqs.maxBytes)

          val sqsAttempt =
            if (small.nonEmpty) writeBatchToSqsWithRetries(small, sqs, minBackoff, maxRetries).void else Sync[F].unit
          val kinesisAttempt =
            if (big.nonEmpty) writeBatchToKinesisWithRetries(big, maxBackoff, Int.MaxValue) else Sync[F].unit
          (sqsAttempt, kinesisAttempt).parTupled.void
        case None =>
          log.error(s"$error. No SQS buffer defined. Retrying to send the events to Kinesis")
          kinesisHealthy = false
          Async[F].sleep(maxBackoff.millis) >> writeBatchToKinesisWithRetries(failedRecords, maxBackoff, maxRetries)
      }
    }

  private def handleSqsError(
    failedRecords: List[Events],
    sqs: Sqs,
    nextBackoff: Long,
    retriesLeft: Int
  ): F[Unit] =
    if (retriesLeft > 0) {
      log.error(
        s"Retrying to write ${failedRecords.size} records to SQS buffer ${sqs.bufferName} in $nextBackoff milliseconds. $retriesLeft retries left"
      )
      val nextNextBackoff = getNextBackoff(nextBackoff)
      Async[F].sleep(nextBackoff.millis) >> writeBatchToSqsWithRetries(
        failedRecords,
        sqs,
        nextNextBackoff,
        retriesLeft - 1
      )
    } else {
      // If SQS was already unhealthy, the background check is already running.
      // It can happen when the collector switches back and forth between Kinesis and SQS.
      if (sqsHealthy) {
        this.synchronized {
          if (sqsHealthy) {
            sqsHealthy = false
            checkSqsHealth()
          }
        }
      }
      log.error(
        s"Maximum number of retries reached for SQS buffer ${sqs.bufferName} for ${failedRecords.size} records. Retrying in Kinesis"
      )
      writeBatchToKinesisWithRetries(failedRecords, minBackoff, maxRetries)
    }

  private def writeBatchToKinesis(batch: List[Events]): F[PutRecordsResponse] = {
    val f = Sync[F].delay {
      val putRecordsRequest = {
        val putRecordsRequestEntryList = batch.map { event =>
          PutRecordsRequestEntry
            .builder()
            .partitionKey(event.key)
            .data(SdkBytes.fromByteArrayUnsafe(event.payloads))
            .build()
        }
        PutRecordsRequest.builder().streamName(streamName).records(putRecordsRequestEntryList.asJava).build()
      }
      client.putRecords(putRecordsRequest)
    }
    Async[F].evalOn(f, ec)
  }

  /**
    * @return Empty list if all events were successfully inserted;
    *         otherwise a non-empty list of Events to be retried and the reasons why they failed.
    */
  private def writeBatchToSqs(batch: List[Events], sqs: Sqs): F[List[(Events, BatchResultErrorInfo)]] = {
    val f = Sync[F].delay {
      val splitBatch = split(batch, MaxSqsBatchSizeN, sqs.maxBytes)
      splitBatch.map(toSqsMessages).flatMap { msgGroup =>
        val entries = msgGroup.map(_._2)
        val batchRequest =
          SendMessageBatchRequest.builder().queueUrl(sqs.bufferName).entries(entries.asJava).build()
        val response = sqs.client.sendMessageBatch(batchRequest)
        val failures = response
          .failed()
          .asScala
          .toList
          .map { bree =>
            (bree.id(), BatchResultErrorInfo(bree.code(), bree.message()))
          }
          .toMap
        // Events to retry and reasons for failure
        msgGroup.collect {
          case (e, m) if failures.contains(m.id()) =>
            (e, failures(m.id()))
        }
      }
    }
    Async[F].evalOn(f, ec)
  }

  private def toSqsMessages(events: List[Events]): List[(Events, SendMessageBatchRequestEntry)] =
    events.map(e =>
      (
        e,
        SendMessageBatchRequestEntry
          .builder()
          .id(UUID.randomUUID.toString)
          .messageBody(b64Encode(e.payloads))
          .messageAttributes(
            Map(
              "kinesisKey" -> MessageAttributeValue.builder().dataType("String").stringValue(e.key).build()
            ).asJava
          )
          .build()
      )
    )

  private def b64Encode(msg: Array[Byte]): String = {
    val buffer = java.util.Base64.getEncoder.encode(msg)
    new String(buffer)
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
    executorService.shutdown()
    executorService.awaitTermination(10000, MILLISECONDS)
    ()
  }

  private def checkKinesisHealth(): Unit = {
    val healthRunnable = new Runnable {
      override def run(): Unit = {
        log.info(s"Starting background check for Kinesis stream $streamName")
        while (!kinesisHealthy) {
          Try {
            val streamDescription = describeStream(client, streamName)
            streamDescription.streamStatusAsString()
          } match {
            case Success("ACTIVE") =>
              log.info(s"Stream $streamName ACTIVE")
              kinesisHealthy = true
            case Success(other) =>
              log.warn(s"Stream $streamName not ACTIVE but $other")
              Thread.sleep(kinesisConfig.startupCheckInterval.toMillis)
            case Failure(err) =>
              log.error(s"Error while checking status of stream $streamName: ${err.getMessage()}")
              Thread.sleep(kinesisConfig.startupCheckInterval.toMillis)
          }
        }
      }
    }
    executorService.execute(healthRunnable)
  }

  private def checkSqsHealth(): Unit = maybeSqs.foreach { sqs =>
    val healthRunnable = new Runnable {
      override def run(): Unit = {
        log.info(s"Starting background check for SQS buffer ${sqs.bufferName}")
        while (!sqsHealthy) {
          Try {
            val req = GetQueueUrlRequest.builder().queueName(sqs.bufferName).build()
            sqs.client.getQueueUrl(req)
          } match {
            case Success(_) =>
              log.info(s"SQS buffer ${sqs.bufferName} exists")
              sqsHealthy = true
            case Failure(err) =>
              log.error(s"SQS buffer ${sqs.bufferName} doesn't exist. Error: ${err.getMessage()}")
          }
          Thread.sleep(kinesisConfig.startupCheckInterval.toMillis)
        }
      }
    }
    executorService.execute(healthRunnable)
  }
}

/** KinesisSink companion object with factory method */
object KinesisSink {

  sealed trait Target
  final case object Kinesis extends Target
  final case class Sqs(client: SqsClient, bufferName: String, maxBytes: Int) extends Target

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

  /**
    * Create a KinesisSink and schedule a task to flush its EventStorage.
    * Exists so that no threads can get a reference to the KinesisSink
    * during its construction.
    */
  def create[F[_]: Async](
    sinkConfig: Config.Sink[KinesisSinkConfig],
    sqsBufferName: Option[String],
    executorService: ExecutorService
  ): Resource[F, KinesisSink[F]] = {
    val acquire =
      Sync[F]
        .delay(
          createAndInitialize(sinkConfig, sqsBufferName, executorService)
        )
        .rethrow
    val release = (sink: KinesisSink[F]) => Sync[F].delay(sink.shutdown())

    Resource.make(acquire)(release)
  }

  /**
    * Creates a new Kinesis client.
    * @param provider aws credentials provider
    * @param endpoint kinesis endpoint where the stream resides
    * @param region aws region where the stream resides
    * @return the initialized AmazonKinesisClient
    */
  def createKinesisClient(
    customEndpoint: Option[String],
    region: String
  ): Either[Throwable, KinesisClient] =
    Either.catchNonFatal {
      val endpointUri = customEndpoint.map(URI.create)
      val builder     = KinesisClient.builder().region(Region.of(region))
      val withEndpointOverride = endpointUri match {
        case None    => builder
        case Some(e) => builder.endpointOverride(e)
      }
      withEndpointOverride.build()
    }

  def describeStream(client: KinesisClient, streamName: String) = {
    val describeRequest = DescribeStreamSummaryRequest.builder().streamName(streamName).build()
    val describeResult  = client.describeStreamSummary(describeRequest)
    describeResult.streamDescriptionSummary()
  }

  /**
    * Create a KinesisSink and schedule a task to flush its EventStorage.
    * Exists so that no threads can get a reference to the KinesisSink
    * during its construction.
    */
  private def createAndInitialize[F[_]: Async](
    sinkConfig: Config.Sink[KinesisSinkConfig],
    sqsBufferName: Option[String],
    executorService: ExecutorService
  ): Either[Throwable, KinesisSink[F]] = {
    val clients = for {
      kinesisClient    <- createKinesisClient(sinkConfig.config.endpoint, sinkConfig.config.region)
      sqsClientAndName <- sqsBuffer(sqsBufferName, sinkConfig.config.region, sinkConfig.config.sqsMaxBytes)
    } yield (kinesisClient, sqsClientAndName)

    clients.map {
      case (kinesisClient, sqsClientAndName) =>
        val ks =
          new KinesisSink(
            sinkConfig.config.maxBytes,
            kinesisClient,
            sinkConfig.config,
            sinkConfig.name,
            executorService,
            sqsClientAndName
          )
        ks.checkKinesisHealth()
        ks.checkSqsHealth()
        ks
    }
  }

  private def sqsBuffer(
    bufferName: Option[String],
    region: String,
    maxBytes: Int
  ): Either[Throwable, Option[Sqs]] =
    bufferName match {
      case Some(name) =>
        createSqsClient(region).map(client => Some(Sqs(client, name, maxBytes)))
      case None => None.asRight
    }

  private def createSqsClient(region: String): Either[Throwable, SqsClient] =
    Either.catchNonFatal(
      SqsClient.builder().region(Region.of(region)).build()
    )

  /**
    * Splits a Kinesis-sized batch of `Events` into smaller batches that meet the SQS limit.
    * @param batch A batch of up to `KinesisLimit` that must be split into smaller batches.
    * @param maxRecords Max records for the smaller batches.
    * @param maxBytes Max byte size for the smaller batches.
    * @return A batch of smaller batches, each one of which meets the limits.
    */
  def split(
    batch: List[Events],
    maxRecords: Int,
    maxBytes: Int
  ): List[List[Events]] = {
    var bytes = 0L
    @scala.annotation.tailrec
    def go(originalBatch: List[Events], tmpBatch: List[Events], newBatch: List[List[Events]]): List[List[Events]] =
      (originalBatch, tmpBatch) match {
        case (Nil, Nil) => newBatch
        case (Nil, acc) => acc :: newBatch
        case (h :: t, acc) if acc.size + 1 > maxRecords || h.payloads.size + bytes > maxBytes =>
          bytes = h.payloads.size.toLong
          go(t, h :: Nil, acc :: newBatch)
        case (h :: t, acc) =>
          bytes += h.payloads.size
          go(t, h :: acc, newBatch)
      }
    go(batch, Nil, Nil).map(_.reverse).reverse.filter(_.nonEmpty)
  }

}
