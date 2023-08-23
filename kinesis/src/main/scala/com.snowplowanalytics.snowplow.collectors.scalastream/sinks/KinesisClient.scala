package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import cats.Parallel
import cats.effect.{Async, Resource, Sync}
import cats.implicits._
import com.amazonaws.auth._
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.kinesis.{AmazonKinesis, AmazonKinesisClientBuilder}

object KinesisClient {
  def create[F[_]: Async: Parallel](
    config: KinesisSinkConfig,
    streamName: String
  ): Resource[F, AmazonKinesis] =
    Resource.eval[F, AmazonKinesis](mkProducer(config, streamName))

  private def mkProducer[F[_]: Sync](
    config: KinesisSinkConfig,
    streamName: String
  ): F[AmazonKinesis] =
    for {
      kinesis <- Sync[F].delay(createKinesisClient(config))
      _       <- streamExists(kinesis, streamName)
    } yield kinesis

  private def streamExists[F[_]: Sync](kinesis: AmazonKinesis, stream: String): F[Unit] =
    for {
      described <- Sync[F].delay(kinesis.describeStream(stream))
      status = described.getStreamDescription.getStreamStatus
      _ <- status match {
        case "ACTIVE" | "UPDATING" =>
          Sync[F].unit
        case _ =>
          Sync[F].raiseError[Unit](new IllegalArgumentException(s"Stream $stream doesn't exist or can't be accessed"))
      }
    } yield ()

  private def createKinesisClient(config: KinesisSinkConfig): AmazonKinesis =
    AmazonKinesisClientBuilder
      .standard()
      .withCredentials(getProvider(config.aws))
      .withEndpointConfiguration(new EndpointConfiguration(config.endpoint, config.region))
      .build()

  private def getProvider(awsConfig: KinesisSinkConfig.AWSConfig): AWSCredentialsProvider = {
    def isDefault(key: String): Boolean = key == "default"

    def isIam(key: String): Boolean = key == "iam"

    def isEnv(key: String): Boolean = key == "env"

    ((awsConfig.accessKey, awsConfig.secretKey) match {
      case (a, s) if isDefault(a) && isDefault(s) =>
        new DefaultAWSCredentialsProviderChain()
      case (a, s) if isDefault(a) || isDefault(s) =>
        throw new IllegalArgumentException("accessKey and secretKey must both be set to 'default' or neither")
      case (a, s) if isIam(a) && isIam(s) =>
        InstanceProfileCredentialsProvider.getInstance()
      case (a, s) if isIam(a) && isIam(s) =>
        throw new IllegalArgumentException("accessKey and secretKey must both be set to 'iam' or neither")
      case (a, s) if isEnv(a) && isEnv(s) =>
        new EnvironmentVariableCredentialsProvider()
      case (a, s) if isEnv(a) || isEnv(s) =>
        throw new IllegalArgumentException("accessKey and secretKey must both be set to 'env' or neither")
      case _ =>
        new AWSStaticCredentialsProvider(
          new BasicAWSCredentials(awsConfig.accessKey, awsConfig.secretKey)
        )
    })
  }

}
