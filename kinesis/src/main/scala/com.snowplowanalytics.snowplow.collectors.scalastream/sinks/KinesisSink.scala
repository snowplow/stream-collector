package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import cats.effect.implicits.genSpawnOps
import cats.effect.{Async, Ref, Resource, Sync}
import cats.implicits._
import cats.{Monoid, Parallel}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.kinesis.model.{PutRecordsRequest, PutRecordsRequestEntry, PutRecordsResult}
import com.amazonaws.services.kinesis.{AmazonKinesis, AmazonKinesisClientBuilder}
import com.snowplowanalytics.snowplow.collector.core.Sink
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.syntax.all._

import java.nio.ByteBuffer
import java.util.UUID
import scala.collection.JavaConverters._

class KinesisSink[F[_]: Async: Parallel: Logger] private (
  override val maxBytes: Int,
  config: KinesisSinkConfig,
  kinesis: AmazonKinesis,
  streamName: String
) extends Sink[F] {
  override def isHealthy: F[Boolean] = Async[F].pure(true) //TODO

  override def storeRawEvents(events: List[Array[Byte]], key: String): F[Unit] =
    writeToKinesis(toKinesisRecords(events)).start.void

  private def writeToKinesis(batch: List[PutRecordsRequestEntry]): F[Unit] =
    for {
      forNextAttemptBuffer <- Ref.of(batch)
      failures <- runAndCaptureFailures(forNextAttemptBuffer).retryingOnFailures(
        policy        = Retries.fibonacci[F](config.backoffPolicy),
        wasSuccessful = failures => Async[F].pure(failures.isEmpty),
        onFailure = {
          case (result, retryDetails) =>
            val msg = failureMessageForThrottling(result, streamName)
            Logger[F].warn(s"$msg (${retryDetails.retriesSoFar} retries from cats-retry)")
        }
      )
      _ <- if (failures.isEmpty) Sync[F].unit
      else Sync[F].raiseError(new RuntimeException(failureMessageForThrottling(failures, streamName)))
    } yield ()

  private def runAndCaptureFailures(
    forNextAttemptBuffer: Ref[F, List[PutRecordsRequestEntry]]
  ): F[List[PutRecordsRequestEntry]] =
    for {
      batch    <- forNextAttemptBuffer.get
      failures <- tryWriteToKinesis(batch)
      _        <- forNextAttemptBuffer.set(failures.toList)
    } yield failures.toList

  private def tryWriteToKinesis(
    records: List[PutRecordsRequestEntry]
  ): F[Vector[PutRecordsRequestEntry]] =
    Logger[F].debug(s"Writing ${records.size} records to $streamName") *>
      Async[F]
        .blocking(putRecords(records))
        .map(TryBatchResult.build(records, _))
        .retryingOnFailuresAndAllErrors(
          policy        = Retries.fullJitter[F](config.backoffPolicy),
          wasSuccessful = r => Async[F].pure(!r.shouldRetrySameBatch),
          onFailure = {
            case (result, retryDetails) =>
              val msg = failureMessageForInternalErrors(records, streamName, result)
              Logger[F].error(s"$msg (${retryDetails.retriesSoFar} retries from cats-retry)")
          },
          onError = (exception, retryDetails) =>
            Logger[F].error(exception)(
              s"Writing ${records.size} records to $streamName errored (${retryDetails.retriesSoFar} retries from cats-retry)"
            )
        )
        .flatMap { result =>
          if (result.shouldRetrySameBatch)
            Sync[F].raiseError(new RuntimeException(failureMessageForInternalErrors(records, streamName, result)))
          else
            result.nextBatchAttempt.pure[F]
        }

  private def toKinesisRecords(records: List[Array[Byte]]): List[PutRecordsRequestEntry] =
    records.map { r =>
      val data = ByteBuffer.wrap(r)
      val prre = new PutRecordsRequestEntry()
      prre.setPartitionKey(UUID.randomUUID().toString)
      prre.setData(data)
      prre
    }

  private case class TryBatchResult(
    nextBatchAttempt: Vector[PutRecordsRequestEntry],
    hadSuccess: Boolean,
    wasThrottled: Boolean,
    exampleInternalError: Option[String]
  ) {
    // Only retry the exact same again if no record was successfully inserted, and all the errors
    // were not throughput exceeded exceptions
    def shouldRetrySameBatch: Boolean =
      !hadSuccess && !wasThrottled
  }

  private object TryBatchResult {

    implicit private def tryBatchResultMonoid: Monoid[TryBatchResult] =
      new Monoid[TryBatchResult] {
        override val empty: TryBatchResult = TryBatchResult(Vector.empty, false, false, None)

        override def combine(x: TryBatchResult, y: TryBatchResult): TryBatchResult =
          TryBatchResult(
            x.nextBatchAttempt ++ y.nextBatchAttempt,
            x.hadSuccess   || y.hadSuccess,
            x.wasThrottled || y.wasThrottled,
            x.exampleInternalError.orElse(y.exampleInternalError)
          )
      }

    def build(records: List[PutRecordsRequestEntry], prr: PutRecordsResult): TryBatchResult =
      if (prr.getFailedRecordCount.toInt =!= 0)
        records.zip(prr.getRecords.asScala).foldMap {
          case (orig, recordResult) =>
            Option(recordResult.getErrorCode) match {
              case None =>
                TryBatchResult(Vector.empty, true, false, None)
              case Some("ProvisionedThroughputExceededException") =>
                TryBatchResult(Vector(orig), false, true, None)
              case Some(_) =>
                TryBatchResult(Vector(orig), false, false, Option(recordResult.getErrorMessage))
            }
        }
      else
        TryBatchResult(Vector.empty, true, false, None)
  }

  private def putRecords(records: List[PutRecordsRequestEntry]): PutRecordsResult = {
    val putRecordsRequest = {
      val prr = new PutRecordsRequest()
      prr.setStreamName(streamName)
      prr.setRecords(records.asJava)
      prr
    }
    kinesis.putRecords(putRecordsRequest)
  }

  private def failureMessageForInternalErrors(
    records: List[PutRecordsRequestEntry],
    streamName: String,
    result: TryBatchResult
  ): String = {
    val exampleMessage = result.exampleInternalError.getOrElse("none")
    s"Writing ${records.size} records to $streamName errored with internal failures. Example error message [$exampleMessage]"
  }

  private def failureMessageForThrottling(
    records: List[PutRecordsRequestEntry],
    streamName: String
  ): String =
    s"Exceeded Kinesis provisioned throughput: ${records.size} records failed writing to $streamName."

}

object KinesisSink {

  implicit private def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  def create[F[_]: Async: Parallel](
    config: KinesisSinkConfig,
    streamName: String
  ): Resource[F, Sink[F]] =
    for {
      producer <- Resource.eval[F, AmazonKinesis](mkProducer(config, streamName))
    } yield new KinesisSink[F](config.maxBytes, config, producer, streamName)

  private def mkProducer[F[_]: Sync](
    config: KinesisSinkConfig,
    streamName: String
  ): F[AmazonKinesis] =
    for {
      builder <- Sync[F].delay(AmazonKinesisClientBuilder.standard)
      withEndpoint <- config.customEndpoint match {
        case Some(endpoint) =>
          Sync[F].delay(builder.withEndpointConfiguration(new EndpointConfiguration(endpoint, config.region)))
        case None =>
          Sync[F].delay(builder.withRegion(config.region))
      }
      kinesis <- Sync[F].delay(withEndpoint.build())
      _       <- streamExists(kinesis, streamName)
    } yield kinesis

  private def streamExists[F[_]: Sync](kinesis: AmazonKinesis, stream: String): F[Unit] =
    for {
      described <- Sync[F].delay(kinesis.describeStream(stream))
      status = described.getStreamDescription.getStreamStatus
      _ <- status match {
        case "ACTIVE" | "UPDATING" =>
          Sync[F].unit
        case _ =>
          Sync[F].raiseError[Unit](new IllegalArgumentException(s"Stream $stream doesn't exist or can't be accessed"))
      }
    } yield ()
}
