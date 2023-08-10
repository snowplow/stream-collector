package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import com.google.api.gax.core.NoCredentialsProvider
import com.google.api.gax.grpc.GrpcTransportChannel
import com.google.api.gax.rpc.FixedTransportChannelProvider
import com.google.cloud.pubsub.v1.{Publisher, TopicAdminSettings}
import io.grpc.ManagedChannelBuilder

object BuilderOps {

  implicit class PublisherBuilderOps(val builder: Publisher.Builder) extends AnyVal {
    def setProvidersForEmulator(): Publisher.Builder =
      customEmulatorHost().fold(builder) { emulatorHost =>
        builder
          .setChannelProvider(createCustomChannelProvider(emulatorHost))
          .setCredentialsProvider(NoCredentialsProvider.create())
      }
  }

  implicit class TopicAdminBuilderOps(val builder: TopicAdminSettings.Builder) extends AnyVal {
    def setProvidersForEmulator(): TopicAdminSettings.Builder =
      customEmulatorHost().fold(builder) { emulatorHost =>
        builder
          .setTransportChannelProvider(createCustomChannelProvider(emulatorHost))
          .setCredentialsProvider(NoCredentialsProvider.create())
      }
  }

  private def customEmulatorHost(): Option[String] =
    sys.env.get("PUBSUB_EMULATOR_HOST")

  private def createCustomChannelProvider(emulatorHost: String): FixedTransportChannelProvider = {
    val channel = ManagedChannelBuilder.forTarget(emulatorHost).usePlaintext().build()
    FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel))
  }
}
