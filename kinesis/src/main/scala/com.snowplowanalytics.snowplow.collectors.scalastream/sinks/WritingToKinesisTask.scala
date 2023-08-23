package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import cats.effect.implicits.genSpawnOps
import cats.effect.kernel.{Outcome, Resource}
import cats.effect.std.Queue
import cats.effect.{Async, Ref, Sync}
import cats.implicits._
import cats.{Monoid, Parallel}
import com.amazonaws.services.kinesis.AmazonKinesis
import com.amazonaws.services.kinesis.model.{PutRecordsRequest, PutRecordsRequestEntry, PutRecordsResult}
import com.snowplowanalytics.snowplow.collector.core.Config
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.KinesisSink.Event
import fs2.Chunk
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.syntax.all._

import java.nio.ByteBuffer
import java.util.UUID
import scala.collection.JavaConverters._

object WritingToKinesisTask {

  implicit private def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  def run[F[_]: Async: Parallel](
    config: KinesisSinkConfig,
    bufferConfig: Config.Buffer,
    streamName: String,
    eventsBuffer: Queue[F, Option[Event]],
    kinesis: AmazonKinesis
  ): Resource[F, F[Outcome[F, Throwable, Unit]]] =
    fs2
      .Stream
      .fromQueueNoneTerminated(eventsBuffer)
      .chunks
      .parEvalMapUnbounded(events => writeToKinesis(events, config, streamName, kinesis, bufferConfig))
      .compile
      .drain
      .background

  // Copied from enrich!
  private def writeToKinesis[F[_]: Async: Parallel](
    chunk: Chunk[Event],
    config: KinesisSinkConfig,
    streamName: String,
    kinesis: AmazonKinesis,
    bufferConfig: Config.Buffer
  ): F[Unit] =
    for {
      forNextAttemptBuffer <- Ref.of(toKinesisRecords(chunk.toList))
      failures <- runAndCaptureFailures(forNextAttemptBuffer, config, kinesis, bufferConfig, streamName)
        .retryingOnFailures(
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

  private def runAndCaptureFailures[F[_]: Async: Parallel](
    ref: Ref[F, List[PutRecordsRequestEntry]],
    config: KinesisSinkConfig,
    kinesis: AmazonKinesis,
    bufferConfig: Config.Buffer,
    streamName: String
  ): F[List[PutRecordsRequestEntry]] =
    for {
      records <- ref.get
      failures <- group(records, bufferConfig.recordLimit, bufferConfig.byteLimit, getRecordSize).parTraverse(g =>
        tryWriteToKinesis(g, config, streamName, kinesis)
      )
      flattened = failures.flatten
      _ <- ref.set(flattened)
    } yield flattened

  private def getRecordSize(record: PutRecordsRequestEntry) =
    record.getData.array.length + record.getPartitionKey.getBytes.length

  private def tryWriteToKinesis[F[_]: Async: Parallel](
    records: List[PutRecordsRequestEntry],
    config: KinesisSinkConfig,
    streamName: String,
    kinesis: AmazonKinesis
  ): F[Vector[PutRecordsRequestEntry]] =
    Logger[F].debug(s"Writing ${records.size} records to $streamName") *>
      Async[F]
        .blocking(putRecords(records, streamName, kinesis))
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

  private def toKinesisRecords(records: List[Event]): List[PutRecordsRequestEntry] =
    records.map { r =>
      val data = ByteBuffer.wrap(r)
      val prre = new PutRecordsRequestEntry()
      prre.setPartitionKey(UUID.randomUUID().toString)
      prre.setData(data)
      prre
    }

  /**
    * This function takes a list of records and splits it into several lists,
    * where each list is as big as possible with respecting the record limit and
    * the size limit.
    */
  private def group[A](
    records: List[A],
    recordLimit: Long,
    sizeLimit: Long,
    getRecordSize: A => Int
  ): List[List[A]] = {
    case class Batch(
      size: Int,
      count: Int,
      records: List[A]
    )

    records
      .foldLeft(List.empty[Batch]) {
        case (acc, record) =>
          val recordSize = getRecordSize(record)
          acc match {
            case head :: tail =>
              if (head.count + 1 > recordLimit || head.size + recordSize > sizeLimit)
                List(Batch(recordSize, 1, List(record))) ++ List(head) ++ tail
              else
                List(Batch(head.size + recordSize, head.count + 1, record :: head.records)) ++ tail
            case Nil =>
              List(Batch(recordSize, 1, List(record)))
          }
      }
      .map(_.records)
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

  private def putRecords(
    records: List[PutRecordsRequestEntry],
    streamName: String,
    kinesis: AmazonKinesis
  ): PutRecordsResult = {
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
