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
package com.snowplowanalytics.snowplow.collector.core

import cats.implicits._
import cats.effect.implicits._
import cats.effect.{Async, Ref, Resource}
import org.typelevel.log4cats.Logger

import com.snowplowanalytics.snowplow.streams.{ListOfList, Sink => CommonStreamsSink}

import scala.concurrent.duration.FiniteDuration

trait Sink[F[_]] {

  /** Maximum number of bytes that a single record can contain.
    *
    *  If {batching+compression} is enabled, then this refers to the size after {batching+compression}
    *
    * If a record is bigger, a size violation bad row is emitted instead
    */
  val maxBytes: Int

  /** If {batching+compression} is enabled, this is the target maximum size of a record.
    *
    *  This is wrapped in a `F` because target size might change over time. E.g. if Kinesis is
    *  unhealthy, then the Kinesis sink might request a smaller target so it can write records to
    *  SQS.
    *
    *  If a record is bigger, then it is emitted anyway, as long as it does not exceed `maxBytes`
    *
    */
  def targetBytes: F[Int]

  def isHealthy: F[Boolean]

  /** Write a batch of messages into the stream
    *
    *  The `Sink` is expected to write the messages immediately with minimal delay. The core
    *  collector has already batched up messages into a sensible sized batch.
    *
    *  The `Sink` must handle all failures by retrying the write.  The returned `F[Unit]` must not
    *  fail.
    */
  def storeRawEvents(events: List[Array[Byte]]): F[Unit]
}

object Sink {

  /** Wrap a common-streams `Sink[F]` as a collector `Sink[F]`
    *
    *  @param name The name of the output topic or stream. Used for logging only.
    *  @param maxBytes Maximum number of bytes that a single record can contain.
    *  @param sinkRetryInterval How soon to retry sinking events after a failure. Note that common-streams sink implementations already do some backoff and retry.
    *  @param startupHealthCheckInterval How soon to retry the startup health check in case the first check fails
    *  @param sink The common-streams sink
    */
  def ofCommonStreamsSink[F[_]: Logger: Async](
    name: String,
    maxBytes: Int,
    sinkRetryInterval: FiniteDuration,
    startupHealthCheckInterval: FiniteDuration,
    sink: CommonStreamsSink[F]
  ): Resource[F, Sink[F]] =
    for {
      ref <- Resource.eval(Ref[F].of(false))
      _   <- initializeHealth(name, startupHealthCheckInterval, sink, ref).background
    } yield new OfCommonStreamsSink(maxBytes, name, sinkRetryInterval, sink, ref)

  private class OfCommonStreamsSink[F[_]: Logger: Async](
    val maxBytes: Int,
    name: String,
    retryInterval: FiniteDuration,
    sink: CommonStreamsSink[F],
    isHealthyState: Ref[F, Boolean]
  ) extends Sink[F] {

    override def isHealthy: F[Boolean] = isHealthyState.get

    override def targetBytes: F[Int] = maxBytes.pure[F]

    override def storeRawEvents(events: List[Array[Byte]]): F[Unit] =
      sink.sinkSimple(ListOfList.of(List(events))).attempt.flatMap {
        case Left(e) =>
          isHealthyState.set(false) >>
            Logger[F].error(s"Error sinking events to $name: ${e.getMessage}") >>
            Async[F].sleep(retryInterval) >>
            storeRawEvents(events)
        case Right(()) =>
          isHealthyState.set(true)
      }
  }

  private def initializeHealth[F[_]: Logger: Async](
    name: String,
    retryInterval: FiniteDuration,
    sink: CommonStreamsSink[F],
    isHealthyState: Ref[F, Boolean]
  ): F[Unit] =
    sink.isHealthy.attempt.flatMap {
      case Right(true) =>
        isHealthyState.set(true)
      case Right(false) =>
        Logger[F].warn(s"Sink $name is not healthy") >> Async[F]
          .sleep(retryInterval) >> initializeHealth(name, retryInterval, sink, isHealthyState)
      case Left(e) =>
        Logger[F].warn(e)(s"Exception when trying to check health of sink $name") >> Async[F]
          .sleep(retryInterval) >> initializeHealth(name, retryInterval, sink, isHealthyState)
    }

}
