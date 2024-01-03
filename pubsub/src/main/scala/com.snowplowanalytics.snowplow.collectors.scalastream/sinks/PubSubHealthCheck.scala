package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import cats.effect.implicits.genSpawnOps
import cats.effect.{Async, Ref, Resource, Sync}
import cats.implicits._
import com.google.cloud.pubsub.v1.{TopicAdminClient, TopicAdminSettings}
import com.google.pubsub.v1.{ProjectName, TopicName}
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.BuilderOps._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.jdk.CollectionConverters._
import scala.util._

object PubSubHealthCheck {

  implicit private def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  def run[F[_]: Async](
    isHealthyState: Ref[F, Boolean],
    sinkConfig: PubSubSinkConfig,
    topicName: String
  ): Resource[F, Unit] =
    for {
      topicAdminClient <- createTopicAdminClient[F]()
      healthCheckTask = createHealthCheckTask[F](topicAdminClient, isHealthyState, sinkConfig, topicName)
      _ <- repeatInBackgroundUntilHealthy(isHealthyState, sinkConfig, healthCheckTask)
    } yield ()

  private def repeatInBackgroundUntilHealthy[F[_]: Async](
    isHealthyState: Ref[F, Boolean],
    sinkConfig: PubSubSinkConfig,
    healthCheckTask: F[Unit]
  ): Resource[F, Unit] = {
    val checkThenSleep = healthCheckTask *> Async[F].sleep(sinkConfig.startupCheckInterval)
    checkThenSleep.untilM_(isHealthyState.get).background.void
  }

  private def createHealthCheckTask[F[_]: Async](
    topicAdminClient: TopicAdminClient,
    isHealthyState: Ref[F, Boolean],
    sinkConfig: PubSubSinkConfig,
    topicName: String
  ): F[Unit] =
    topicExists(topicAdminClient, sinkConfig.googleProjectId, topicName).flatMap {
      case Right(true) =>
        Logger[F].info(s"Topic $topicName exists") *> isHealthyState.set(true)
      case Right(false) =>
        Logger[F].error(s"Topic $topicName doesn't exist")
      case Left(err) =>
        Logger[F].error(s"Error while checking if topic $topicName exists: ${err.getCause}")
    }

  private def createTopicAdminClient[F[_]: Sync](): Resource[F, TopicAdminClient] = {
    val builder = TopicAdminSettings.newBuilder().setProvidersForEmulator().build()
    Resource.make(Sync[F].delay(TopicAdminClient.create(builder)))(client => Sync[F].delay(client.close()))
  }

  private def topicExists[F[_]: Sync](
    topicAdmin: TopicAdminClient,
    projectId: String,
    topicName: String
  ): F[Either[Throwable, Boolean]] = Sync[F].delay {
    Either
      .catchNonFatal(topicAdmin.listTopics(ProjectName.of(projectId)))
      .leftMap(new RuntimeException(s"Can't list topics", _))
      .map(_.iterateAll.asScala.toList.map(_.getName()))
      .flatMap { topics =>
        topics.contains(TopicName.of(projectId, topicName).toString).asRight
      }
  }

}
