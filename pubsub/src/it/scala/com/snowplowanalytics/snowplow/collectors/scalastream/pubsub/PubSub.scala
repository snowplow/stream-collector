/*
 * Copyright (c) 2022-2022 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, and
 * you may not use this file except in compliance with the Apache License
 * Version 2.0.  You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Apache License Version 2.0 is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream.pubsub

import scala.collection.JavaConverters._

import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.{FixedTransportChannelProvider, TransportChannelProvider}
import com.google.api.gax.core.{CredentialsProvider, NoCredentialsProvider}
import com.google.cloud.pubsub.v1.{
  SubscriptionAdminClient,
  SubscriptionAdminSettings,
  TopicAdminClient,
  TopicAdminSettings
}
import com.google.pubsub.v1.{
  PullRequest,
  PushConfig,
  ProjectSubscriptionName,
  SubscriptionName,
  TopicName
}

import io.grpc.{ManagedChannel, ManagedChannelBuilder}

import cats.effect.{IO, Resource}

import com.snowplowanalytics.snowplow.badrows.BadRow

import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload

import utils._

object PubSub {

  def createTopicsAndSubscriptions(
    projectId: String,
    emulatorHost: String,
    emulatorPort: Int,
    topics: List[String]
  ): Unit = {
    val providers = mkProviders(emulatorHost, emulatorPort)
    try {
      createTopics(providers, projectId, topics)
      createSubscriptions(providers, projectId, topics)
    } finally {
      providers.channel.shutdownNow()
      ()
    }
  }

  private def mkProviders(
    emulatorHost: String,
    emulatorPort: Int
  ): Providers = {
    val channel = ManagedChannelBuilder.forTarget(s"$emulatorHost:$emulatorPort").usePlaintext().build()
    val channelProvider = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel))
    val credentialsProvider = NoCredentialsProvider.create()
    Providers(channel, channelProvider, credentialsProvider)
  }

  private def createTopics(
    providers: Providers,
    projectId: String,
    topics: List[String]
  ): Unit = {
    val topicAdmin = TopicAdminClient.create(
      TopicAdminSettings
        .newBuilder()
        .setTransportChannelProvider(providers.channelProvider)
        .setCredentialsProvider(providers.credentialsProvider)
        .build()
    )
    try {
      topics.foreach { topic =>
        val topicName = TopicName.of(projectId, topic)
        topicAdmin.createTopic(topicName)
      }
    } finally {
      topicAdmin.close()
    }
  }

  private def createSubscriptions(
    providers: Providers,
    projectId: String,
    subscriptions: List[String]
  ): Unit = {
    val pushConfig = PushConfig.newBuilder().build()
    val ackDeadlineSeconds = 60
    val subscriptionAdmin = mkSubscriptionAdmin(providers)
    try {
      subscriptions.foreach { subscription =>
        val topicName = TopicName.of(projectId, subscription)
        val subscriptionName = SubscriptionName.of(projectId, subscription)
        subscriptionAdmin.createSubscription(
          subscriptionName,
          topicName,
          pushConfig,
          ackDeadlineSeconds
        )
      }
    } finally {
      subscriptionAdmin.close()
    }
  }

  private def mkSubscriptionAdmin(providers: Providers): SubscriptionAdminClient =
    SubscriptionAdminClient.create(
      SubscriptionAdminSettings
        .newBuilder()
        .setTransportChannelProvider(providers.channelProvider)
        .setCredentialsProvider(providers.credentialsProvider)
        .build()
    )

  def consume(
    projectId: String,
    emulatorHost: String,
    emulatorPort: Int,
    subscriptionGood: String,
    subscriptionBad: String
  ): IO[CollectorOutput] = {
    val subscriptionAdmin = for {
      providers <- resourceProviders(emulatorHost, emulatorPort)
      subscriptionAdmin <- resourceSubscriptionAdmin(providers)
    } yield subscriptionAdmin

    subscriptionAdmin.use { subAdmin =>
      for {
        goodRaw <- pull(subAdmin, projectId, subscriptionGood)
        good <- IO(goodRaw.map(parseCollectorPayload))
        badRaw <- pull(subAdmin, projectId, subscriptionBad)
        bad <- IO(badRaw.map(parseBadRow))
      } yield CollectorOutput(good, bad)
    }
  }

  private def resourceProviders(
    emulatorHost: String,
    emulatorPort: Int
  ): Resource[IO, Providers] =
    Resource.make {
      IO(mkProviders(emulatorHost, emulatorPort))
    } { p =>
      IO(p.channel.shutdownNow()).void
    }

  private def resourceSubscriptionAdmin(providers: Providers): Resource[IO, SubscriptionAdminClient] =
    Resource.make {
      IO(mkSubscriptionAdmin(providers))
    } { admin =>
      IO(admin.close())
    }

  private def pull(
    subscriptionAdmin: SubscriptionAdminClient,
    projectId: String,
    subscription: String,
    previous: List[Array[Byte]] = Nil
  ): IO[List[Array[Byte]]] =
    onePull(subscriptionAdmin, projectId, subscription).flatMap {
      case list if list.nonEmpty => pull(subscriptionAdmin, projectId, subscription, previous ++ list)
      case _ => IO.pure(previous)
    }

  private def onePull(
    subscriptionAdmin: SubscriptionAdminClient,
    projectId: String,
    subscription: String
  ): IO[List[Array[Byte]]] = {
    val pullRequest = PullRequest.newBuilder()
      .setSubscription(ProjectSubscriptionName.of(projectId, subscription).toString())
      .setMaxMessages(Int.MaxValue)
      .build()
    IO(subscriptionAdmin.pull(pullRequest))
      .map(_.getReceivedMessagesList().asScala.toList.map(_.getMessage().getData().toByteArray()))
  }

  case class Providers(
    channel: ManagedChannel,
    channelProvider: TransportChannelProvider,
    credentialsProvider: CredentialsProvider
  )

  case class CollectorOutput(
    good: List[CollectorPayload],
    bad: List[BadRow]
  )
}
