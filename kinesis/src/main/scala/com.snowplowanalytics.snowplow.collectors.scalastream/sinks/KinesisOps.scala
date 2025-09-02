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
import software.amazon.awssdk.awscore.defaultsmode.DefaultsMode
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.http.async.SdkAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.awssdk.services.kinesis.model.{
  DescribeStreamSummaryRequest,
  DescribeStreamSummaryResponse,
  PutRecordsRequest,
  PutRecordsRequestEntry,
  PutRecordsResponse,
  PutRecordsResultEntry
}

import scala.jdk.CollectionConverters._
import java.net.URI
import java.util.UUID

class KinesisOps[F[_]: Async] private (client: KinesisAsyncClient, streamName: String, refHealth: Ref[F, Boolean]) {

  implicit private def logger: Logger[F] = Slf4jLogger.getLogger[F]

  /** Writes events to Kinesis and returns the list of events that failed to be written.
    *
    * @param events List of raw event bytes to write to Kinesis
    * @return The list of events that failed to be written. Reasons for failure include rate limiting and any caught exception.
    */
  def write(events: List[Array[Byte]]): F[List[Array[Byte]]] =
    if (events.isEmpty) {
      List.empty[Array[Byte]].pure[F]
    } else {
      val putRecordsRequest = buildPutRecordsRequest(events)

      Async[F]
        .fromCompletableFuture(Async[F].delay(client.putRecords(putRecordsRequest)))
        .flatMap(response => processResponse(events, response))
        .handleErrorWith { error =>
          logger.error(
            s"Writing ${events.size} records to Kinesis stream $streamName failed with exception. Failures will be retried. Exception message: ${error.getMessage}"
          ) >>
            refHealth.set(false) >>
            events.pure[F]
        }
    }

  /** Returns the current health status of the Kinesis connection.
    *
    * The health status is updated based on write operation results:
    * - Set to `true` when all events are successfully written to Kinesis
    * - Set to `false` when any events fail to write or an exception occurs
    *
    * @return Effect containing `true` if Kinesis is healthy, `false` otherwise
    */
  def isHealthy: F[Boolean] =
    refHealth.get

  def describeStreamSummary: F[DescribeStreamSummaryResponse] = {
    val request = DescribeStreamSummaryRequest.builder().streamName(streamName).build()
    Async[F].fromCompletableFuture(Async[F].delay(client.describeStreamSummary(request)))
  }

  /** Checks if the Kinesis stream exists and is in ACTIVE status.
    *
    * This function makes a single attempt to describe the stream and check its status.
    * All exceptions are caught and handled gracefully.
    *
    * @return Effect containing `true` if the stream exists and is ACTIVE, `false` if the stream
    *         doesn't exist, is not ACTIVE, or any error occurs (including permission errors, network issues, etc.)
    *
    * On success with ACTIVE status: Logs an info message and sets health to true
    * On success with non-ACTIVE status: Logs a warning message and sets health to false
    * On error: Logs an error message with the exception details and sets health to false
    */
  def checkStreamExists: F[Boolean] =
    describeStreamSummary.attempt.flatMap {
      case Right(response) =>
        response.streamDescriptionSummary().streamStatusAsString() match {
          case "ACTIVE" =>
            logger.info(s"Kinesis stream $streamName is ACTIVE") >>
              refHealth.set(true).as(true)
          case other =>
            logger.warn(s"Kinesis stream $streamName is not ACTIVE but $other") >>
              refHealth.set(false).as(false)
        }
      case Left(error) =>
        logger.error(s"Error while checking status of Kinesis stream $streamName: ${error.getMessage}") >>
          refHealth.set(false).as(false)
    }

  private def buildPutRecordsRequest(events: List[Array[Byte]]): PutRecordsRequest = {
    val requestEntries = events.map { eventBytes =>
      PutRecordsRequestEntry
        .builder()
        .partitionKey(UUID.randomUUID.toString)
        .data(SdkBytes.fromByteArrayUnsafe(eventBytes))
        .build()
    }

    PutRecordsRequest.builder().streamName(streamName).records(requestEntries.asJava).build()
  }

  private def processResponse(
    originalEvents: List[Array[Byte]],
    response: PutRecordsResponse
  ): F[List[Array[Byte]]] = {
    val recordResults = response.records().asScala.toList
    val failurePairs = originalEvents.zip(recordResults).collect {
      case (originalEvent, recordResult) if Option(recordResult.errorCode()).isDefined =>
        (originalEvent, recordResult)
    }

    if (failurePairs.nonEmpty) {
      for {
        _ <- logger.debug {
          val successCount = originalEvents.size - failurePairs.size
          s"Successfully wrote $successCount out of ${originalEvents.size} records to Kinesis stream $streamName"
        }
        _ <- refHealth.set(false)
        _ <- logErrorDetails(failurePairs, originalEvents.size)
      } yield failurePairs.map(_._1)
    } else {
      for {
        _ <- logger.debug(s"Successfully wrote all ${originalEvents.size} records to Kinesis stream $streamName")
        _ <- refHealth.set(true)
      } yield List.empty[Array[Byte]]
    }
  }

  private def logErrorDetails(
    failurePairs: List[(Array[Byte], PutRecordsResultEntry)],
    totalRecords: Int
  ): F[Unit] =
    failurePairs.groupBy(_._2.errorCode()).toList.traverse_ {
      case (errorCode, items) =>
        val exampleMsg = items.map(_._2.errorMessage()).find(_ != null).filter(_.nonEmpty).getOrElse("")
        logger.warn(
          s"Writing ${items.size} records (out of $totalRecords) to Kinesis stream $streamName failed with error code [$errorCode]. Failures will be retried. Example message: $exampleMsg"
        )
    }
}

object KinesisOps {

  def resource[F[_]: Async](
    httpClient: SdkAsyncHttpClient,
    streamName: String,
    config: KinesisSinkConfig
  ): Resource[F, KinesisOps[F]] =
    for {
      client    <- createClient(httpClient, config)
      healthRef <- Resource.eval(Ref[F].of(false))
    } yield new KinesisOps(client, streamName, healthRef)

  private def createClient[F[_]: Sync](
    httpClient: SdkAsyncHttpClient,
    config: KinesisSinkConfig
  ): Resource[F, KinesisAsyncClient] =
    Resource.fromAutoCloseable {
      Sync[F].delay {
        val builder = KinesisAsyncClient
          .builder()
          .httpClient(httpClient)
          .defaultsMode(DefaultsMode.AUTO)
          .region(Region.of(config.region))
        config.endpoint.foreach(endpoint => builder.endpointOverride(URI.create(endpoint)))
        builder.build()
      }
    }
}
