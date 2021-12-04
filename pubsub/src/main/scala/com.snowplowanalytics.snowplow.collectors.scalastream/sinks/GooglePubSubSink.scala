/*
 * Copyright (c) 2013-2021 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.collectors.scalastream
package sinks

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.time.Instant
import com.google.api.core.{ApiFutureCallback, ApiFutures}
import com.google.api.gax.batching.BatchingSettings
import com.google.api.gax.retrying.RetrySettings
import com.google.api.gax.rpc.{ApiException, FixedHeaderProvider}
import com.google.cloud.pubsub.v1.{Publisher, TopicAdminClient}
import com.google.pubsub.v1.{ProjectName, ProjectTopicName, PubsubMessage}
import com.google.protobuf.ByteString
import org.threeten.bp.Duration

import scala.collection.JavaConverters._
import scala.util._

import cats.syntax.either._

import com.snowplowanalytics.snowplow.collectors.scalastream.model._

/**
  * Google PubSub Sink for the Scala Stream Collector
  */
class GooglePubSubSink private (
  publisher: Publisher,
  topicName: String,
  throttler: Sink.Throttler,
  healthTimeout: Duration
) extends Sink.Throttled(throttler) {
  import GooglePubSubSink.OutageMonitor

  private val logExecutor = Executors.newSingleThreadExecutor()

  // maximum size of a pubsub message is 10MB
  override val MaxBytes: Int = 10000000

  private val outage = new AtomicReference(OutageMonitor(true, None))

  // Is the collector detecting an outage downstream
  override def isHealthy: Boolean = {
    val cutoff = Instant.now.minusMillis(healthTimeout.toMillis)
    outage.get match {
      case OutageMonitor(_, Some(outstanding)) if outstanding.isBefore(cutoff) =>
        // No message has been recently published, but one is overdue
        false
      case OutageMonitor(healthy, _) =>
        healthy
    }
  }

  /**
    * Store raw events in the PubSub topic
    * @param events The list of events to send
    * @param key Not used.
    */
  override def storeRawEventsThrottled(events: List[Array[Byte]], key: String): Unit = {
    if (events.nonEmpty)
      log.debug(s"Writing ${events.size} Thrift records to Google PubSub topic $topicName.")
    val futures = events.map { event =>
      val future = publisher.publish(eventToPubsubMessage(event))
      ApiFutures.addCallback(
        future,
        new ApiFutureCallback[String]() {
          override def onSuccess(messageId: String): Unit = {
            log.debug(s"Successfully published event with id $messageId to $topicName.")
            onComplete(event.size.toLong)
          }

          override def onFailure(throwable: Throwable): Unit = {
            throwable match {
              case apiEx: ApiException =>
                log.error(
                  s"Publishing message to $topicName failed with code ${apiEx.getStatusCode}: ${apiEx.getMessage} This error is retryable: ${apiEx.isRetryable}."
                )
              case t => log.error(s"Publishing message to $topicName failed with ${t.getMessage}.")
            }
            outage.set(OutageMonitor(false, None))
            onComplete(event.size.toLong)
          }
        },
        logExecutor
      )
      future
    }

    if (events.nonEmpty) {
      outage.getAndUpdate {
        case OutageMonitor(healthy, None) => OutageMonitor(healthy, Some(Instant.now))
        case other                        => other
      }
      ApiFutures.addCallback(
        ApiFutures.allAsList(futures.asJava),
        new ApiFutureCallback[java.util.List[String]]() {
          override def onSuccess(i: java.util.List[String]): Unit =
            outage.set(OutageMonitor(true, None))
          override def onFailure(throwable: Throwable): Unit = ()
        },
        logExecutor
      )
    }
  }

  /**
    * Convert event bytes to a PubsubMessage to be published
    * @param event Event to be converted
    * @return a PubsubMessage
    */
  private def eventToPubsubMessage(event: Array[Byte]): PubsubMessage =
    PubsubMessage.newBuilder.setData(ByteString.copyFrom(event)).build()
}

/** GooglePubSubSink companion object with factory method */
object GooglePubSubSink {
  def createAndInitialize(
    googlePubSubConfig: GooglePubSub,
    bufferConfig: BufferConfig,
    topicName: String,
    enableStartupChecks: Boolean,
    throttler: Sink.Throttler
  ): Either[Throwable, GooglePubSubSink] =
    for {
      batching <- batchingSettings(bufferConfig).asRight
      retry = retrySettings(googlePubSubConfig.backoffPolicy)
      publisher <- createPublisher(googlePubSubConfig.googleProjectId, topicName, batching, retry)
      _ <- if (enableStartupChecks) topicExists(googlePubSubConfig.googleProjectId, topicName).flatMap { b =>
        if (b) ().asRight
        else new IllegalArgumentException(s"Google PubSub topic $topicName doesn't exist").asLeft
      } else ().asRight
    } yield new GooglePubSubSink(
      publisher,
      topicName,
      throttler,
      Duration.ofMillis(googlePubSubConfig.backoffPolicy.initialRpcTimeout)
    )

  private val UserAgent = s"snowplow/stream-collector-${generated.BuildInfo.version}"

  /**
    * Instantiates a Publisher on an existing topic with the given configuration options.
    * This can fail if the publisher can't be created.
    * @return a PubSub publisher or an error
    */
  private def createPublisher(
    projectId: String,
    topicName: String,
    batchingSettings: BatchingSettings,
    retrySettings: RetrySettings
  ): Either[Throwable, Publisher] =
    Either.catchNonFatal(
      Publisher
        .newBuilder(ProjectTopicName.of(projectId, topicName))
        .setBatchingSettings(batchingSettings)
        .setRetrySettings(retrySettings)
        .setHeaderProvider(FixedHeaderProvider.create("User-Agent", UserAgent))
        .build()
    )

  private def batchingSettings(bufferConfig: BufferConfig): BatchingSettings =
    BatchingSettings
      .newBuilder()
      .setElementCountThreshold(bufferConfig.recordLimit)
      .setRequestByteThreshold(bufferConfig.byteLimit)
      .setDelayThreshold(Duration.ofMillis(bufferConfig.timeLimit))
      .build()

  /** Defaults are used for the rpc configuration, see Publisher.java */
  private def retrySettings(backoffPolicy: GooglePubSubBackoffPolicyConfig): RetrySettings =
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

  /** Checks that a PubSub topic exists **/
  private def topicExists(projectId: String, topicName: String): Either[Throwable, Boolean] =
    for {
      topicAdminClient <- Either.catchNonFatal(TopicAdminClient.create())
      topics <- Either
        .catchNonFatal(topicAdminClient.listTopics(ProjectName.of(projectId)))
        .map(_.iterateAll.asScala.toList)
      exists = topics.map(_.getName).exists(_.contains(topicName))
      _ <- Either.catchNonFatal(topicAdminClient.close())
    } yield exists

  private case class OutageMonitor(healthy: Boolean, outstanding: Option[Instant])
}
