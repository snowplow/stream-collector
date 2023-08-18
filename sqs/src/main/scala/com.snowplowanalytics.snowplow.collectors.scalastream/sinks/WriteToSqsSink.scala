package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import scala.concurrent.duration._
import scala.collection.JavaConverters._

import java.util.UUID

import cats.{Monoid, Parallel, Semigroup}
import cats.syntax.all._

import cats.effect.syntax.all._
import cats.effect._
import cats.effect.kernel.Outcome
import cats.effect.std.Queue

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import fs2.{Pipe, Pull, Stream}

import retry.syntax.all._

import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model._

import com.snowplowanalytics.snowplow.collector.core.Config

object WriteToSqsSink {

  implicit private def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  private val MaxSqsBatchSizeN = 10

  /**
    * Events to be written to SQS.
    *
    * @param payloads Serialized events extracted from a CollectorPayload.
    *                 The size of this collection is limited by MaxBytes.
    *                 Not to be confused with a 'batch' events to sink.
    * @param key      Partition key for Kinesis, when events are ultimately re-routed there
    */
  final case class Events(payloads: Array[Byte], key: String)

  // Details about why messages failed to be written to SQS.
  final case class BatchResultErrorInfo(code: String, message: String)

  def run[F[_]: Async: Parallel](
    client: SqsAsyncClient,
    queueName: String,
    eventsBuffer: Queue[F, Option[Events]],
    config: Config.Streams[SqsSinkConfig]
  ): Resource[F, F[Outcome[F, Throwable, Unit]]] =
    Stream
      .fromQueueNoneTerminated[F, Events](eventsBuffer)
      .map(e => Batched(List(e), e.payloads.length.toLong))
      .through(batchUp(config.buffer))
      .parEvalMapUnbounded(writeToSqs[F](client, queueName, config.sink)) //TODO: Use parEvalMapUnordered
      .compile
      .drain
      .background

  def writeToSqs[F[_]: Async: Parallel](
    client: SqsAsyncClient,
    queueName: String,
    config: SqsSinkConfig
  )(batch: Batched): F[Unit] =
    for {
      forNextAttemptBuffer <- Ref.of(toSqsMessages(batch.events))
      failures <- runAndCaptureFailures(forNextAttemptBuffer, client, queueName, config).retryingOnFailures(
        policy        = Retries.fibonacci[F](config.backoffPolicy),
        wasSuccessful = failures => Async[F].pure(failures.isEmpty),
        onFailure = {
          case (result, retryDetails) =>
            val msg = failureMessage(result, queueName)
            Logger[F].warn(s"$msg (${retryDetails.retriesSoFar} retries from cats-retry)")
        }
      )
      _ <- if (failures.isEmpty) Sync[F].unit
      else Sync[F].raiseError(new RuntimeException(failureMessage(failures, queueName)))
    } yield ()

  private def runAndCaptureFailures[F[_]: Async: Parallel](
    ref: Ref[F, List[SendMessageBatchRequestEntry]],
    client: SqsAsyncClient,
    queueName: String,
    config: SqsSinkConfig
  ): F[List[SendMessageBatchRequestEntry]] =
    for {
      records  <- ref.get
      failures <- records.grouped(MaxSqsBatchSizeN).toList.parTraverse(g => tryWriteToSqs(g, client, queueName, config))
      flattened = failures.flatten
      _ <- ref.set(flattened)
    } yield flattened

  def tryWriteToSqs[F[_]: Async](
    events: List[SendMessageBatchRequestEntry],
    client: SqsAsyncClient,
    queueName: String,
    config: SqsSinkConfig
  ): F[Vector[SendMessageBatchRequestEntry]] =
    Logger[F].debug(s"Writing ${events.size} records to $queueName") *>
      Async[F]
        .fromCompletableFuture {
          Async[F].delay {
            val batchRequest = SendMessageBatchRequest.builder().queueUrl(queueName).entries(events.asJava).build()
            client.sendMessageBatch(batchRequest)
          }
        }
        .map(TryBatchResult.build(events, _))
        .retryingOnFailuresAndAllErrors(
          policy        = Retries.fullJitter[F](config.backoffPolicy),
          wasSuccessful = r => Async[F].pure(!r.shouldRetrySameBatch),
          onFailure = {
            case (result, retryDetails) =>
              val msg = result.failureMessageForInternalErrors(events, queueName)
              Logger[F].error(s"$msg (${retryDetails.retriesSoFar} retries from cats-retry)")
          },
          onError = (exception, retryDetails) =>
            Logger[F].error(exception)(
              s"${failureMessage(events, queueName)} (${retryDetails.retriesSoFar} retries from cats-retry)"
            )
        )
        .flatMap { result =>
          if (result.shouldRetrySameBatch)
            Sync[F].raiseError(new RuntimeException(result.failureMessageForInternalErrors(events, queueName)))
          else
            result.nextBatchAttempt.pure[F]
        }

  private case class TryBatchResult(
    nextBatchAttempt: Vector[SendMessageBatchRequestEntry],
    hadSuccess: Boolean,
    exampleInternalError: Option[String]
  ) {
    // Only retry the exact same again if no record was successfully inserted
    def shouldRetrySameBatch: Boolean = !hadSuccess

    def failureMessageForInternalErrors[A](
      records: List[A],
      queueName: String
    ): String = {
      val exampleMessage = exampleInternalError.getOrElse("none")
      s"Writing ${records.size} records to $queueName errored with internal failures. Example error message [$exampleMessage]"
    }
  }

  private object TryBatchResult {

    implicit def tryBatchResultMonoid: Monoid[TryBatchResult] =
      new Monoid[TryBatchResult] {
        override val empty: TryBatchResult = TryBatchResult(Vector.empty, false, None)

        override def combine(x: TryBatchResult, y: TryBatchResult): TryBatchResult =
          TryBatchResult(
            x.nextBatchAttempt ++ y.nextBatchAttempt,
            x.hadSuccess || y.hadSuccess,
            x.exampleInternalError.orElse(y.exampleInternalError)
          )
      }

    def build(records: List[SendMessageBatchRequestEntry], prr: SendMessageBatchResponse): TryBatchResult =
      if (prr.failed().size() =!= 0) {
        val failures = prr
          .failed()
          .asScala
          .toList
          .map { bree =>
            (bree.id(), BatchResultErrorInfo(bree.code(), bree.message()))
          }
          .toMap

        records.foldMap { r =>
          failures.get(r.id()) match {
            case Some(err) => TryBatchResult(Vector(r), false, Option(err.message))
            case None      => TryBatchResult(Vector.empty, true, None)
          }
        }
      } else
        TryBatchResult(Vector.empty, true, None)
  }

  def toSqsMessages(events: List[Events]): List[SendMessageBatchRequestEntry] =
    events.map(e =>
      SendMessageBatchRequestEntry
        .builder()
        .id(UUID.randomUUID.toString)
        .messageBody(b64Encode(e.payloads))
        .messageAttributes(
          Map(
            "kinesisKey" ->
              MessageAttributeValue.builder().dataType("String").stringValue(e.key).build()
          ).asJava
        )
        .build()
    )

  def b64Encode(e: Array[Byte]): String = {
    val buffer = java.util.Base64.getEncoder.encode(e)
    new String(buffer)
  }

  def failureMessage[A](records: List[A], queueName: String): String =
    s"Writing ${records.size} records to $queueName errored"

  case class Batched(
    events: List[Events],
    originalBytes: Long
  )

  object Batched {
    implicit def batchedSemigroup[F[_]]: Semigroup[Batched] = new Semigroup[Batched] {
      def combine(x: Batched, y: Batched): Batched =
        Batched(
          x.events |+| y.events,
          x.originalBytes + y.originalBytes
        )
    }
  }

  // TODO: This function is taken from microfile-loader project. It would be good to
  // put this one in a common place since it will be used in a few other place as well.
  def batchUp[F[_]: Async](config: Config.Buffer): Pipe[F, Batched, Batched] = {

    def go(
      timedPull: Pull.Timed[F, Batched],
      batched: Option[Batched]
    ): Pull[F, Batched, Unit] =
      timedPull.uncons.flatMap {
        case None =>
          batched match {
            case Some(b) => Pull.output1[F, Batched](b) >> Pull.done
            case None    => Pull.done
          }
        case Some((Left(_), next)) =>
          batched match {
            case Some(b) => Pull.output1(b) >> go(next, None)
            case None    => go(next, None)
          }
        case Some((Right(pulled), next)) if pulled.isEmpty =>
          go(next, batched)
        case Some((Right(pulled), next)) =>
          val combined = batched match {
            case Some(b) => pulled.foldLeft(b)(_ |+| _)
            case None    => pulled.iterator.reduce(_ |+| _) // we have already checked that chunk is non-empty
          }
          val maybeStartTimer = if (batched.isEmpty) timedPull.timeout(config.timeLimit.milliseconds) else Pull.pure(())
          if (combined.originalBytes > config.byteLimit)
            Pull.output1(combined) >> go(next, None)
          else
            maybeStartTimer >> go(next, Some(combined))
      }

    source =>
      source
        .pull
        .timed { timedPull =>
          go(timedPull, None)
        }
        .stream
  }
}
