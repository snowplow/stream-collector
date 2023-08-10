/*
 * Copyright (c) 2013-2023 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import cats.Parallel
import cats.effect.implicits.genSpawnOps
import cats.effect.{Async, Ref, Resource, Sync}
import cats.implicits._
import com.google.api.gax.retrying.RetrySettings
import com.google.api.gax.rpc.{ApiException, FixedHeaderProvider}
import com.permutive.pubsub.producer.Model.{ProjectId, Topic}
import com.permutive.pubsub.producer.encoder.MessageEncoder
import com.permutive.pubsub.producer.grpc.{GooglePubsubProducer, PubsubProducerConfig}
import com.permutive.pubsub.producer.{Model, PubsubProducer}
import com.snowplowanalytics.snowplow.collector.core.{Config, Sink}
import com.snowplowanalytics.snowplow.collectors.scalastream.BuildInfo
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.BuilderOps._
import org.threeten.bp.Duration
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies
import retry.syntax.all._

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util._

class PubSubSink[F[_]: Async: Parallel: Logger] private (
  override val maxBytes: Int,
  isHealthyState: Ref[F, Boolean],
  producer: PubsubProducer[F, Array[Byte]],
  retryInterval: FiniteDuration,
  topicName: String
) extends Sink[F] {

  override def storeRawEvents(events: List[Array[Byte]], key: String): F[Unit] =
    produceBatch(events).start.void

  override def isHealthy: F[Boolean] = isHealthyState.get

  private def produceBatch(events: List[Array[Byte]]): F[Unit] =
    events.parTraverse_ { event =>
      produceSingleEvent(event)
    } *> isHealthyState.set(true)

  private def produceSingleEvent(event: Array[Byte]): F[Model.MessageId] =
    producer
      .produce(event)
      .retryingOnAllErrors(
        policy  = RetryPolicies.constantDelay(retryInterval),
        onError = (error, _) => handlePublishError(error)
      )

  private def handlePublishError(error: Throwable): F[Unit] =
    isHealthyState.set(false) *> Logger[F].error(createErrorMessage(error))

  private def createErrorMessage(error: Throwable): String =
    error match {
      case apiEx: ApiException =>
        val retryable = if (apiEx.isRetryable) "retryable" else "non-retryable"
        s"Publishing message to $topicName failed with code ${apiEx.getStatusCode} and $retryable error: ${apiEx.getMessage}"
      case throwable => s"Publishing message to $topicName failed with error: ${throwable.getMessage}"
    }
}

object PubSubSink {

  implicit private def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  implicit val byteArrayEncoder: MessageEncoder[Array[Byte]] =
    new MessageEncoder[Array[Byte]] {
      def encode(a: Array[Byte]): Either[Throwable, Array[Byte]] =
        a.asRight
    }

  def create[F[_]: Async: Parallel](
    maxBytes: Int,
    sinkConfig: PubSubSinkConfig,
    bufferConfig: Config.Buffer,
    topicName: String
  ): Resource[F, Sink[F]] =
    for {
      isHealthyState <- Resource.eval(Ref.of[F, Boolean](false))
      producer       <- createProducer[F](sinkConfig, topicName, bufferConfig)
      _              <- PubSubHealthCheck.run(isHealthyState, sinkConfig, topicName)
    } yield new PubSubSink(
      maxBytes,
      isHealthyState,
      producer,
      sinkConfig.retryInterval,
      topicName
    )

  private def createProducer[F[_]: Async: Parallel](
    sinkConfig: PubSubSinkConfig,
    topicName: String,
    bufferConfig: Config.Buffer
  ): Resource[F, PubsubProducer[F, Array[Byte]]] = {
    val config = PubsubProducerConfig[F](
      batchSize            = bufferConfig.recordLimit,
      requestByteThreshold = Some(bufferConfig.byteLimit),
      delayThreshold       = bufferConfig.timeLimit.millis,
      onFailedTerminate    = err => Logger[F].error(err)("PubSub sink termination error"),
      customizePublisher = Some {
        _.setRetrySettings(retrySettings(sinkConfig.backoffPolicy))
          .setHeaderProvider(FixedHeaderProvider.create("User-Agent", BuildInfo.dockerAlias))
          .setProvidersForEmulator()
      }
    )

    GooglePubsubProducer.of[F, Array[Byte]](ProjectId(sinkConfig.googleProjectId), Topic(topicName), config)
  }

  private def retrySettings(backoffPolicy: PubSubSinkConfig.BackoffPolicy): RetrySettings =
    RetrySettings
      .newBuilder()
      .setInitialRetryDelay(Duration.ofMillis(backoffPolicy.minBackoff))
      .setMaxRetryDelay(Duration.ofMillis(backoffPolicy.maxBackoff))
      .setRetryDelayMultiplier(backoffPolicy.multiplier)
      .setTotalTimeout(Duration.ofMillis(backoffPolicy.totalBackoff))
      .setInitialRpcTimeout(Duration.ofMillis(backoffPolicy.initialRpcTimeout))
      .setRpcTimeoutMultiplier(backoffPolicy.rpcTimeoutMultiplier)
      .setMaxRpcTimeout(Duration.ofMillis(backoffPolicy.maxRpcTimeout))
      .build()
}
