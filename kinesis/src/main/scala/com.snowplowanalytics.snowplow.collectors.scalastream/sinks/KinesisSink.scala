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
import cats.effect.implicits._
import cats.effect.{Async, Ref, Resource}
import software.amazon.awssdk.http.async.SdkAsyncHttpClient

import com.snowplowanalytics.snowplow.collector.core.{Config, Sink}

import scala.concurrent.duration.DurationLong

class KinesisSink[F[_]: Async](
  kinesisConfig: KinesisSinkConfig,
  kinesisOps: KinesisOps[F],
  sqsOpsOpt: Option[SqsOps[F]],
  state: KinesisSink.State[F]
) extends Sink[F] {
  import KinesisSink._

  override val maxBytes: Int = kinesisConfig.maxBytes

  override def isHealthy: F[Boolean] =
    (kinesisOps.isHealthy, sqsOpsOpt.traverse(_.isHealthy)).mapN {
      case (kinesisHealthy, sqsHealthy) => kinesisHealthy || sqsHealthy.contains(true)
    }

  override def targetBytes: F[Int] =
    (state.failoverCounter.get, kinesisOps.isHealthy).mapN {
      case (counter, kinesisHealthy) =>
        if ((counter > 0) || !kinesisHealthy) kinesisConfig.sqsMaxBytes else maxBytes
    }

  override def storeRawEvents(events: List[Array[Byte]]): F[Unit] =
    kinesisOps.write(events).flatMap {
      case Nil => Async[F].unit
      case failures =>
        enterFailoverLoop(failures)
    }

  private def enterFailoverLoop(events: List[Array[Byte]]): F[Unit] =
    state.withIncrementedFailoverCounter.surround {
      sqsOpsOpt match {
        case Some(sqsOps) =>
          val (big, small) = events.partition(_.size > kinesisConfig.sqsMaxBytes)
          val f1           = if (big.nonEmpty) kinesisOnlyFailoverLoop(big) else Async[F].unit
          val f2           = if (small.nonEmpty) kinesisAndSqsFailoverLoop(sqsOps, small) else Async[F].unit
          (f1, f2).parTupled.void
        case None =>
          kinesisOnlyFailoverLoop(events)
      }
    }

  private def kinesisOnlyFailoverLoop(events: List[Array[Byte]]): F[Unit] =
    withFibonacciBackoff(kinesisConfig.backoffPolicy, events) { events =>
      kinesisOps.write(events).map {
        case Nil      => None
        case failures => Some(failures)
      }
    }

  private def kinesisAndSqsFailoverLoop(sqsOps: SqsOps[F], events: List[Array[Byte]]): F[Unit] = {

    def trySqs(events: List[Array[Byte]]): F[Unit] =
      sqsOps.write(events).flatMap {
        case Nil      => Async[F].unit
        case failures => tryKinesis(failures)
      }

    def tryKinesis(events: List[Array[Byte]]): F[Unit] =
      kinesisOps.write(events).flatMap {
        case Nil      => Async[F].unit
        case failures => trySqs(failures)
      }

    trySqs(events)
  }
}

object KinesisSink {

  private class State[F[_]](
    val failoverCounter: Ref[F, Int]
  ) {
    def withIncrementedFailoverCounter(implicit F: cats.Functor[F]): Resource[F, Unit] =
      Resource.make(failoverCounter.update(_ + 1))(_ => failoverCounter.update(_ - 1))
  }

  /** Retries an action with Fibonacci-based backoff delays.
    *
    * This function iteratively invokes the provided action, sleeping between iterations with
    * exponentially increasing delays based on the Fibonacci sequence. The backoff duration
    * starts at `minBackoff` and grows by multiplying `minBackoff` by successive Fibonacci
    * numbers (1, 1, 2, 3, 5, 8, 13, ...), capped at `maxBackoff`.
    *
    * @param config The backoff policy configuration containing `minBackoff` and `maxBackoff` values
    * @param initial The initial input value to pass to the first invocation of `action`
    * @param action A function that processes the current value and returns:
    *               - `None` to terminate the retry loop
    *               - `Some(next)` to continue with `next` as the input for the next iteration
    * @return An effect that completes when the action returns `None`
    *
    * @note The function sleeps BEFORE invoking the action on each iteration (including the first)
    * @note The sleep duration follows the pattern: minBackoff * fib(n), capped by maxBackoff
    */
  def withFibonacciBackoff[F[_]: Async, A](config: KinesisSinkConfig.BackoffPolicy, initial: A)(
    action: A => F[Option[A]]
  ): F[Unit] = {
    def loop(current: A, fib1: Int, fib2: Int): F[Unit] = {
      val duration = (config.minBackoff * fib1).min(config.maxBackoff).millis
      Async[F].sleep(duration).flatMap { _ =>
        action(current).flatMap {
          case None       => Async[F].unit
          case Some(next) => loop(next, fib2, fib1 + fib2)
        }
      }
    }

    loop(initial, 1, 1)
  }

  /** Repeatedly checks if a Kinesis stream exists and is ACTIVE until it becomes available.
    *
    * This function will continue checking indefinitely until the stream exists and is in ACTIVE status.
    * Between each check, it sleeps for the configured startup check interval.
    *
    * @param kinesisOps The KinesisOps instance to check stream existence and status
    * @param config The Kinesis sink configuration containing the check interval
    * @return Effect that completes successfully when the stream exists and is ACTIVE
    */
  private def waitForKinesisStreamExists[F[_]: Async](
    kinesisOps: KinesisOps[F],
    config: KinesisSinkConfig
  ): F[Unit] =
    kinesisOps.checkStreamExists.flatMap { exists =>
      if (exists) {
        Async[F].unit
      } else {
        Async[F].sleep(config.startupCheckInterval) >>
          waitForKinesisStreamExists(kinesisOps, config)
      }
    }

  /** Repeatedly checks if an SQS topic exists until it becomes available.
    *
    * This function will continue checking indefinitely until the topic exists or becomes accessible.
    * Between each check, it sleeps for the configured startup check interval.
    *
    * @param sqsOps The SqsOps instance to check topic existence
    * @param config The Kinesis sink configuration containing the check interval
    * @return Effect that completes successfully when the topic exists and is accessible
    */
  private def waitForSqsTopicExists[F[_]: Async](
    sqsOps: SqsOps[F],
    config: KinesisSinkConfig
  ): F[Unit] =
    sqsOps.checkTopicExists.flatMap { exists =>
      if (exists) {
        Async[F].unit
      } else {
        Async[F].sleep(config.startupCheckInterval) >>
          waitForSqsTopicExists(sqsOps, config)
      }
    }

  /** Creates a KinesisSink wrapped in a Resource for proper cleanup.
    *
    * @param httpClient The SdkAsyncHttpClient to use for AWS SDK clients
    * @param sinkConfig The sink configuration containing stream name and Kinesis config
    * @param sqsTopicName Optional SQS topic name for failover buffer
    * @return Resource-wrapped KinesisSink instance
    */
  def resource[F[_]: Async](
    httpClient: SdkAsyncHttpClient,
    sinkConfig: Config.Sink[KinesisSinkConfig],
    sqsTopicName: Option[String]
  ): Resource[F, KinesisSink[F]] =
    for {
      kinesisOps <- KinesisOps.resource(httpClient, sinkConfig.name, sinkConfig.config)
      sqsOpsOpt <- sqsTopicName.traverse(topicName =>
        SqsOps.resource(httpClient, topicName, sinkConfig.config.sqsMaxBytes, sinkConfig.config)
      )
      failoverCounterRef <- Resource.eval(Ref[F].of(0))
      _                  <- waitForKinesisStreamExists(kinesisOps, sinkConfig.config).background
      _                  <- sqsOpsOpt.traverse(sqsOps => waitForSqsTopicExists(sqsOps, sinkConfig.config).background)
      state = new State(failoverCounterRef)
    } yield new KinesisSink(sinkConfig.config, kinesisOps, sqsOpsOpt, state)

}
