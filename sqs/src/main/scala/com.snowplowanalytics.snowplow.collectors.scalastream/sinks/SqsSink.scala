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
package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import cats.effect.{Async, Resource, Sync}
import cats.implicits._
import cats.effect.implicits._

import org.slf4j.LoggerFactory

import java.util.UUID
import java.util.concurrent.ExecutorService

import scala.util.{Failure, Random, Success, Try}
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.duration.{DurationLong, MILLISECONDS}
import scala.jdk.CollectionConverters._

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model._

import com.snowplowanalytics.snowplow.collector.core.{Config, Sink}

class SqsSink[F[_]: Async] private (
  val maxBytes: Int,
  client: SqsClient,
  sqsConfig: SqsSinkConfig,
  queueName: String,
  executorService: ExecutorService
) extends Sink[F] {
  import SqsSink._

  private lazy val log = LoggerFactory.getLogger(getClass())

  private val maxBackoff: Long        = sqsConfig.backoffPolicy.maxBackoff
  private val minBackoff: Long        = sqsConfig.backoffPolicy.minBackoff
  private val maxRetries: Int         = sqsConfig.backoffPolicy.maxRetries
  private val randomGenerator: Random = new java.util.Random()

  private val MaxSqsBatchSizeN = 10

  lazy val ec: ExecutionContextExecutorService =
    concurrent.ExecutionContext.fromExecutorService(executorService)

  @volatile private var sqsHealthy: Boolean = false
  override def isHealthy: F[Boolean]        = Sync[F].delay(sqsHealthy)

  override def storeRawEvents(events: List[Array[Byte]]): F[Unit] =
    events
      .traverse { bytes =>
        Sync[F].delay(UUID.randomUUID).map(uuid => Events(bytes, uuid.toString))
      }
      .flatMap(withKeys => sinkBatch(withKeys, minBackoff, maxRetries))
      .start
      .void

  private def sinkBatch(batch: List[Events], nextBackoff: Long, retriesLeft: Int): F[Unit] =
    if (batch.nonEmpty) {
      log.info(s"Writing ${batch.size} records to SQS queue $queueName")

      writeBatchToSqs(batch).attempt.flatMap {
        case Right(s) =>
          sqsHealthy = true
          log.info(s"Successfully wrote ${batch.size - s.size} out of ${batch.size} records to SQS queue $queueName")

          if (s.nonEmpty) {
            s.groupBy(_._2.code).foreach {
              case (errorCode, items) =>
                val exampleMsg = items.map(_._2.message).find(_.nonEmpty).getOrElse("")
                log.error(
                  s"Writing ${items.size} records to SQS queue $queueName failed with error code [$errorCode] and example message: $exampleMsg"
                )
            }
            val failedRecords = s.map(_._1)
            handleError(failedRecords, nextBackoff, retriesLeft)
          } else {
            Sync[F].unit
          }
        case Left(f) =>
          log.error(
            s"Writing ${batch.size} records to SQS queue $queueName failed with error: ${f.getMessage()}"
          )
          handleError(batch, nextBackoff, retriesLeft)
      }
    } else {
      Sync[F].unit
    }

  private def handleError(failedRecords: List[Events], nextBackoff: Long, retriesLeft: Int): F[Unit] =
    if (retriesLeft > 0) {
      log.error(
        s"$retriesLeft retries left. Retrying to write ${failedRecords.size} records to SQS queue $queueName in $nextBackoff milliseconds"
      )
      val nextNextBackoff = getNextBackoff(nextBackoff)
      Sync[F].sleep(nextBackoff.millis) >> sinkBatch(failedRecords, nextNextBackoff, retriesLeft - 1)
    } else {
      sqsHealthy = false
      checkSqsHealth()
      log.error(
        s"Maximum number of retries reached for SQS queue $queueName for ${failedRecords.size} records"
      )
      Sync[F].sleep(maxBackoff.millis) >> sinkBatch(failedRecords, maxBackoff, maxRetries)
    }

  /**
    * @return Empty list if all events were successfully inserted;
    *         otherwise a non-empty list of Events to be retried and the reasons why they failed.
    */
  private def writeBatchToSqs(batch: List[Events]): F[List[(Events, BatchResultErrorInfo)]] = {
    val f = Sync[F].delay {
      toSqsMessages(batch)
        .grouped(MaxSqsBatchSizeN)
        .flatMap { msgGroup =>
          val entries = msgGroup.map(_._2)
          val batchRequest =
            SendMessageBatchRequest.builder().queueUrl(queueName).entries(entries.asJava).build()
          val response = client.sendMessageBatch(batchRequest)
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
        .toList
    }
    Async[F].evalOn(f, ec)
  }

  private def toSqsMessages(events: List[Events]): List[(Events, SendMessageBatchRequestEntry)] =
    events.map(e =>
      (
        e,
        SendMessageBatchRequestEntry
          .builder
          .id(e.key)
          .messageBody(b64Encode(e.payloads))
          .messageAttributes(
            Map(
              "kinesisKey" -> MessageAttributeValue.builder().dataType("String").stringValue(e.key).build()
            ).asJava
          )
          .build()
      )
    )

  private def b64Encode(e: Array[Byte]): String = {
    val buffer = java.util.Base64.getEncoder.encode(e)
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

  private def checkSqsHealth(): Unit = {
    val healthRunnable = new Runnable {
      override def run(): Unit =
        while (!sqsHealthy) {
          Try {
            val req = GetQueueUrlRequest.builder().queueName(queueName).build()
            client.getQueueUrl(req)
          } match {
            case Success(_) =>
              log.info(s"SQS queue $queueName exists")
              sqsHealthy = true
            case Failure(err) =>
              log.error(s"SQS queue $queueName doesn't exist. Error: ${err.getMessage()}")
              Thread.sleep(1000L)
          }
        }
    }
    executorService.execute(healthRunnable)
  }
}

/** SqsSink companion object with factory method */
object SqsSink {

  /**
    * Events to be written to SQS.
    * @param payloads Serialized events extracted from a CollectorPayload.
    *                 The size of this collection is limited by MaxBytes.
    *                 Not to be confused with a 'batch' events to sink.
    * @param key Partition key for Kinesis, when events are ultimately re-routed there
    */
  final case class Events(payloads: Array[Byte], key: String)

  // Details about why messages failed to be written to SQS.
  final case class BatchResultErrorInfo(code: String, message: String)

  def create[F[_]: Async](
    sqsConfig: Config.Sink[SqsSinkConfig],
    executorService: ExecutorService
  ): Resource[F, SqsSink[F]] = {
    val acquire =
      Sync[F]
        .delay(
          createAndInitialize(sqsConfig, executorService)
        )
        .rethrow
    val release = (sink: SqsSink[F]) => Sync[F].delay(sink.shutdown())

    Resource.make(acquire)(release)
  }

  def createSqsClient(region: String): Either[Throwable, SqsClient] =
    Either.catchNonFatal(
      SqsClient.builder().region(Region.of(region)).build
    )

  /**
    * Create an SqsSink and schedule a task to check its health
    * Exists so that no threads can get a reference to the SqsSink
    * during its construction.
    */
  def createAndInitialize[F[_]: Async](
    sqsConfig: Config.Sink[SqsSinkConfig],
    executorService: ExecutorService
  ): Either[Throwable, SqsSink[F]] =
    createSqsClient(sqsConfig.config.region).map { c =>
      val sqsSink =
        new SqsSink(sqsConfig.config.maxBytes, c, sqsConfig.config, sqsConfig.name, executorService)
      sqsSink.checkSqsHealth()
      sqsSink
    }
}
