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
package com.snowplowanalytics.snowplow.collectors.scalastream.it.pubsub

import scala.jdk.CollectionConverters._

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

import cats.implicits._

import cats.effect.{IO, Resource}

import com.snowplowanalytics.snowplow.collectors.scalastream.it.utils._
import com.snowplowanalytics.snowplow.collectors.scalastream.it.CollectorOutput

object PubSub {

  def createTopicsAndSubscriptions(
    projectId: String,
    emulatorHost: String,
    emulatorPort: Int,
    topics: List[String]
  ): IO[Unit] =
    resourceProviders(emulatorHost, emulatorPort).use { providers =>
      createTopics(providers, projectId, topics) *>
        createSubscriptions(providers, projectId, topics)
    }

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
      for {
        channel <- IO(ManagedChannelBuilder.forTarget(s"$emulatorHost:$emulatorPort").usePlaintext().build())
        channelProvider <- IO(FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
        credentialsProvider <- IO(NoCredentialsProvider.create())
      } yield Providers(channel, channelProvider, credentialsProvider)
    } { p =>
      IO(p.channel.shutdownNow()).void
    }

  private def createTopics(
    providers: Providers,
    projectId: String,
    topics: List[String]
  ): IO[Unit] =
    resourceTopicAdmin(providers).use { topicAdmin =>
      topics.traverse_ { topic =>
        val topicName = TopicName.of(projectId, topic)
        IO(topicAdmin.createTopic(topicName))
      }
    }

  private def resourceTopicAdmin(providers: Providers): Resource[IO, TopicAdminClient] =
    Resource.make {
      IO(
        TopicAdminClient.create(
          TopicAdminSettings
            .newBuilder()
            .setTransportChannelProvider(providers.channelProvider)
            .setCredentialsProvider(providers.credentialsProvider)
            .build()
        )
      )
    } { admin =>
      IO(admin.close())
    }

  private def createSubscriptions(
    providers: Providers,
    projectId: String,
    subscriptions: List[String]
  ): IO[Unit] =
    resourceSubscriptionAdmin(providers).use { subscriptionAdmin =>
      val pushConfig = PushConfig.newBuilder().build()
      val ackDeadlineSeconds = 60

      subscriptions.traverse_ { subscription =>
        val topicName = TopicName.of(projectId, subscription)
        val subscriptionName = SubscriptionName.of(projectId, subscription)

        IO(
          subscriptionAdmin.createSubscription(
            subscriptionName,
            topicName,
            pushConfig,
            ackDeadlineSeconds
          )
        )
      }
    }

  private def resourceSubscriptionAdmin(providers: Providers): Resource[IO, SubscriptionAdminClient] =
    Resource.make {
      IO(
        SubscriptionAdminClient.create(
          SubscriptionAdminSettings
            .newBuilder()
            .setTransportChannelProvider(providers.channelProvider)
            .setCredentialsProvider(providers.credentialsProvider)
            .build()
        )
      )
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
}
