/*
 * Copyright (c) 2013-2022 Snowplow Analytics Ltd. All rights reserved.
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

import scala.collection.JavaConverters._
import scala.util._

import org.threeten.bp.Duration

import com.google.api.core.{ApiFutureCallback, ApiFutures}
import com.google.api.gax.batching.BatchingSettings
import com.google.api.gax.retrying.RetrySettings
import com.google.api.gax.core.{CredentialsProvider, NoCredentialsProvider}
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.{
  ApiException,
  FixedHeaderProvider,
  FixedTransportChannelProvider,
  TransportChannelProvider
}
import com.google.cloud.pubsub.v1.{Publisher, TopicAdminClient, TopicAdminSettings}
import com.google.pubsub.v1.{ProjectName, ProjectTopicName, PubsubMessage, TopicName}
import com.google.protobuf.ByteString

import io.grpc.ManagedChannelBuilder

import cats.syntax.either._

import com.snowplowanalytics.snowplow.collectors.scalastream.model._

/**
  * Google PubSub Sink for the Scala Stream Collector
  */
class GooglePubSubSink private (val maxBytes: Int, publisher: Publisher, topicName: String) extends Sink {
  private val logExecutor = Executors.newSingleThreadExecutor()

  // Is the collector detecting an outage downstream
  @volatile private var outage: Boolean = true
  override def isHealthy: Boolean       = !outage

  /**
    * Store raw events in the PubSub topic
    * @param events The list of events to send
    * @param key Not used.
    */
  override def storeRawEvents(events: List[Array[Byte]], key: String): Unit = {
    if (events.nonEmpty)
      log.debug(s"Writing ${events.size} Thrift records to Google PubSub topic $topicName.")
    events.foreach { event =>
      publisher.asRight.map { p =>
        val future = p.publish(eventToPubsubMessage(event))
        ApiFutures.addCallback(
          future,
          new ApiFutureCallback[String]() {
            override def onSuccess(messageId: String): Unit = {
              outage = false
              log.debug(s"Successfully published event with id $messageId to $topicName.")
            }

            override def onFailure(throwable: Throwable): Unit = {
              outage = true
              throwable match {
                case apiEx: ApiException =>
                  log.error(
                    s"Publishing message to $topicName failed with code ${apiEx.getStatusCode}: ${apiEx.getMessage} This error is retryable: ${apiEx.isRetryable}."
                  )
                case t => log.error(s"Publishing message to $topicName failed with ${t.getMessage}.")
              }
            }
          },
          logExecutor
        )
      }
    }
  }

  override def shutdown(): Unit =
    publisher.shutdown()

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
    maxBytes: Int,
    googlePubSubConfig: GooglePubSub,
    bufferConfig: BufferConfig,
    topicName: String,
    enableStartupChecks: Boolean
  ): Either[Throwable, GooglePubSubSink] =
    for {
      batching <- batchingSettings(bufferConfig).asRight
      retry = retrySettings(googlePubSubConfig.backoffPolicy)
      customProviders = sys.env.get("PUBSUB_EMULATOR_HOST").map { hostPort =>
        val channel             = ManagedChannelBuilder.forTarget(hostPort).usePlaintext().build()
        val channelProvider     = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel))
        val credentialsProvider = NoCredentialsProvider.create()
        (channelProvider, credentialsProvider)
      }
      _ <- if (enableStartupChecks) {
        val topicAdmin = createTopicAdmin(customProviders)
        try {
          topicExists(topicAdmin, googlePubSubConfig.googleProjectId, topicName)
        } finally {
          Either.catchNonFatal(topicAdmin.close())
          ()
        }
      } else ().asRight
      publisher <- createPublisher(googlePubSubConfig.googleProjectId, topicName, batching, retry, customProviders)
    } yield new GooglePubSubSink(maxBytes, publisher, topicName)

  private val UserAgent = s"snowplow/stream-collector-${generated.BuildInfo.version}"

  /**
    * Instantiates a Publisher on a topic with the given configuration options.
    * This can fail if the publisher can't be created.
    * @return a PubSub publisher or an error
    */
  private def createPublisher(
    projectId: String,
    topicName: String,
    batchingSettings: BatchingSettings,
    retrySettings: RetrySettings,
    customProviders: Option[(TransportChannelProvider, CredentialsProvider)]
  ): Either[Throwable, Publisher] = {
    val builder = Publisher
      .newBuilder(ProjectTopicName.of(projectId, topicName))
      .setBatchingSettings(batchingSettings)
      .setRetrySettings(retrySettings)
      .setHeaderProvider(FixedHeaderProvider.create("User-Agent", UserAgent))
    customProviders.foreach {
      case (channelProvider, credentialsProvider) =>
        builder.setChannelProvider(channelProvider).setCredentialsProvider(credentialsProvider)
    }
    Either.catchNonFatal(builder.build()).leftMap(e => new RuntimeException("Couldn't build PubSub publisher", e))
  }

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

  private def createTopicAdmin(
    customProviders: Option[(TransportChannelProvider, CredentialsProvider)]
  ): TopicAdminClient =
    customProviders match {
      case Some((channelProvider, credentialsProvider)) =>
        TopicAdminClient.create(
          TopicAdminSettings
            .newBuilder()
            .setTransportChannelProvider(channelProvider)
            .setCredentialsProvider(credentialsProvider)
            .build()
        )
      case None =>
        TopicAdminClient.create()
    }

  /** Checks that a PubSub topic exists **/
  private def topicExists(topicAdmin: TopicAdminClient, projectId: String, topicName: String): Either[Throwable, Unit] =
    Either
      .catchNonFatal(topicAdmin.listTopics(ProjectName.of(projectId)))
      .leftMap(new RuntimeException(s"Can't list topics", _))
      .map(_.iterateAll.asScala.toList.map(_.getName()))
      .flatMap { topics =>
        if (topics.contains(TopicName.of(projectId, topicName).toString()))
          ().asRight
        else
          new IllegalStateException(s"PubSub topic $topicName doesn't exist").asLeft
      }
}
