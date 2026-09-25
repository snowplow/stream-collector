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

import cats.implicits._
import cats.effect.{Async, Ref, Resource, Sync}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption
import software.amazon.awssdk.awscore.defaultsmode.DefaultsMode
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{
  BatchResultErrorEntry,
  GetQueueUrlRequest,
  SendMessageBatchRequest,
  SendMessageBatchRequestEntry,
  SendMessageBatchResponse
}

import scala.jdk.CollectionConverters._
import java.util.{Base64, UUID}
import java.nio.charset.StandardCharsets

class SqsOps[F[_]: Async] private (
  client: SqsAsyncClient,
  topicName: String,
  sqsMaxBytes: Int,
  refHealth: Ref[F, Boolean]
) {
  import SqsOps._

  implicit private def logger: Logger[F] = Slf4jLogger.getLogger[F]

  private val MaxSqsBatchSizeN = 10

  /** Writes events to SQS and returns the list of events that failed to be written.
    *
    * @param events List of raw event bytes to write to SQS
    * @return The list of events that failed to be written. Reasons for failure include any caught exception.
    */
  def write(events: List[Array[Byte]]): F[List[Array[Byte]]] =
    if (events.isEmpty) {
      List.empty[Array[Byte]].pure[F]
    } else {
      val splitBatches = split(events, MaxSqsBatchSizeN, sqsMaxBytes)
      splitBatches.traverse(writeBatch).map(_.flatten)
    }

  private def writeBatch(events: List[Array[Byte]]): F[List[Array[Byte]]] = {
    val requestEntries = buildRequestEntries(events)
    val sendMessageBatchRequest =
      SendMessageBatchRequest.builder().queueUrl(topicName).entries(requestEntries.asJava).build()

    Async[F]
      .fromCompletableFuture(Async[F].delay(client.sendMessageBatch(sendMessageBatchRequest)))
      .flatMap(response => processResponse(events, requestEntries, response))
      .handleErrorWith { error =>
        logger.error(
          s"Writing ${events.size} records to SQS topic $topicName failed with exception. Failures will be retried. Exception message: ${error.getMessage}"
        ) >>
          refHealth.set(false) >>
          events.pure[F]
      }
  }

  /** Returns the current health status of the SQS connection.
    *
    * The health status is updated based on write operation results:
    * - Set to `true` when all events are successfully written to SQS
    * - Set to `false` when any events fail to write or an exception occurs
    *
    * @return Effect containing `true` if SQS is healthy, `false` otherwise
    */
  def isHealthy: F[Boolean] =
    refHealth.get

  /** Checks if the SQS topic exists by attempting to get the queue URL.
    *
    * This function makes a single attempt to retrieve the queue URL for the configured topic.
    * All exceptions are caught and handled gracefully.
    *
    * @return Effect containing `true` if the topic exists and is accessible, `false` if the topic
    *         doesn't exist or any error occurs (including permission errors, network issues, etc.)
    *
    * On success: Logs an info message confirming the topic exists
    * On error: Logs an error message with the exception details and returns false
    */
  def checkTopicExists: F[Boolean] = {
    val request = GetQueueUrlRequest.builder().queueName(topicName).build()

    Async[F].fromCompletableFuture(Async[F].delay(client.getQueueUrl(request))).attempt.flatMap {
      case Right(_) =>
        logger.info(s"SQS topic $topicName exists") >>
          refHealth.set(true).as(true)
      case Left(error) =>
        logger.error(s"SQS topic $topicName doesn't exist. Error: ${error.getMessage}") >>
          refHealth.set(false).as(false)
    }
  }

  private def buildRequestEntries(events: List[Array[Byte]]): List[SendMessageBatchRequestEntry] =
    events.map { eventBytes =>
      SendMessageBatchRequestEntry.builder().id(UUID.randomUUID.toString).messageBody(base64Encode(eventBytes)).build()
    }

  private def processResponse(
    originalEvents: List[Array[Byte]],
    originalRequestEntries: List[SendMessageBatchRequestEntry],
    response: SendMessageBatchResponse
  ): F[List[Array[Byte]]] = {
    val failedEntries = response.failed().asScala.toList

    if (failedEntries.isEmpty) {
      // Fast path for the common case - no failures
      for {
        _ <- logger.debug(s"Successfully wrote all ${originalEvents.size} records to SQS topic $topicName")
        _ <- refHealth.set(true)
      } yield List.empty[Array[Byte]]
    } else {
      // Handle failures - do the expensive ID matching
      val failurePairs = failedEntries.flatMap { failedEntry =>
        originalRequestEntries.zip(originalEvents).find(_._1.id() == failedEntry.id()).map {
          case (_, originalEvent) => (originalEvent, failedEntry)
        }
      }

      for {
        _ <- logger.debug {
          val successCount = originalEvents.size - failurePairs.size
          s"Successfully wrote $successCount out of ${originalEvents.size} records to SQS topic $topicName. Failures will be retried."
        }
        _ <- refHealth.set(false)
        _ <- logErrorDetails(failurePairs, originalEvents.size)
      } yield failurePairs.map(_._1)
    }
  }

  private def logErrorDetails(
    failurePairs: List[(Array[Byte], BatchResultErrorEntry)],
    totalRecords: Int
  ): F[Unit] =
    failurePairs.groupBy(_._2.code()).toList.traverse_ {
      case (errorCode, items) =>
        val exampleMsg = items.map(_._2.message()).find(_ != null).filter(_.nonEmpty).getOrElse("")
        logger.warn(
          s"Writing ${items.size} records (out of $totalRecords) to SQS topic $topicName failed with error code [$errorCode]. Failures will be retried. Example message: $exampleMsg"
        )
    }

  private def base64Encode(bytes: Array[Byte]): String = {
    val buffer = Base64.getEncoder.encode(bytes)
    new String(buffer, StandardCharsets.UTF_8)
  }

}

object SqsOps {

  def resource[F[_]: Async](
    httpClient: SdkAsyncHttpClient,
    topicName: String,
    sqsMaxBytes: Int,
    config: KinesisSinkConfig
  ): Resource[F, SqsOps[F]] =
    for {
      client    <- createClient(httpClient, config)
      healthRef <- Resource.eval(Ref[F].of(false))
    } yield new SqsOps(client, topicName, sqsMaxBytes, healthRef)

  private def createClient[F[_]: Sync](
    httpClient: SdkAsyncHttpClient,
    config: KinesisSinkConfig
  ): Resource[F, SqsAsyncClient] =
    Resource.fromAutoCloseable {
      Sync[F].delay {
        SqsAsyncClient
          .builder()
          .httpClient(httpClient)
          .defaultsMode(DefaultsMode.AUTO)
          .region(Region.of(config.region))
          .overrideConfiguration { c =>
            c.putAdvancedOption(SdkAdvancedClientOption.USER_AGENT_PREFIX, KinesisOps.AWS_USER_AGENT)
            ()
          }
          .build()
      }
    }

  /** Splits a batch of events into smaller batches that meet the SQS limits.
    * @param batch A batch of events that must be split into smaller batches.
    * @param maxRecords Max records for the smaller batches.
    * @param maxBytes Max byte size for the smaller batches.
    * @return A batch of smaller batches, each one of which meets the limits.
    */
  def split(
    batch: List[Array[Byte]],
    maxRecords: Int,
    maxBytes: Int
  ): List[List[Array[Byte]]] = {
    var bytes = 0L
    @scala.annotation.tailrec
    def go(
      originalBatch: List[Array[Byte]],
      tmpBatch: List[Array[Byte]],
      newBatch: List[List[Array[Byte]]]
    ): List[List[Array[Byte]]] =
      (originalBatch, tmpBatch) match {
        case (Nil, Nil) => newBatch
        case (Nil, acc) => acc :: newBatch
        case (h :: t, acc) if acc.size + 1 > maxRecords || h.size + bytes > maxBytes =>
          bytes = h.size.toLong
          go(t, h :: Nil, acc :: newBatch)
        case (h :: t, acc) =>
          bytes += h.size
          go(t, h :: acc, newBatch)
      }
    go(batch, Nil, Nil).filter(_.nonEmpty)
  }
}
