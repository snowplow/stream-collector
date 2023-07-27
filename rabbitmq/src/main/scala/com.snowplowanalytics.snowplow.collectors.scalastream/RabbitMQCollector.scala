/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.0
 * located at https://docs.snowplow.io/limited-use-license-1.0
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.syntax.either._

import scala.concurrent.ExecutionContext

import java.util.concurrent.Executors

import com.rabbitmq.client.{Channel, Connection, ConnectionFactory}

import com.snowplowanalytics.snowplow.collectors.scalastream.telemetry.TelemetryAkkaService
import com.snowplowanalytics.snowplow.collectors.scalastream.generated.BuildInfo
import com.snowplowanalytics.snowplow.collectors.scalastream.model._
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.RabbitMQSink

object RabbitMQCollector extends Collector {
  def appName      = BuildInfo.shortName
  def appVersion   = BuildInfo.version
  def scalaVersion = BuildInfo.scalaVersion

  def main(args: Array[String]): Unit = {
    val (collectorConf, akkaConf) = parseConfig(args)
    val telemetry                 = TelemetryAkkaService.initWithCollector(collectorConf, BuildInfo.moduleName, appVersion)
    val sinks: Either[Throwable, CollectorSinks] =
      for {
        config <- collectorConf.streams.sink match {
          case rabbit: Rabbitmq => rabbit.asRight
          case _                => new IllegalArgumentException("Configured sink is not RabbitMQ").asLeft
        }
        rabbitMQ <- initRabbitMQ(config)
        (connection, channel) = rabbitMQ
        _                     = Runtime.getRuntime().addShutdownHook(shutdownHook(connection, channel))
        threadPool            = initThreadPool(config.threadPoolSize)
        goodSink <- RabbitMQSink.init(
          config.maxBytes,
          channel,
          collectorConf.streams.good,
          config.backoffPolicy,
          threadPool
        )
        badSink <- RabbitMQSink.init(
          config.maxBytes,
          channel,
          collectorConf.streams.bad,
          config.backoffPolicy,
          threadPool
        )
      } yield CollectorSinks(goodSink, badSink)

    sinks match {
      case Right(s) => run(collectorConf, akkaConf, s, telemetry)
      case Left(e) =>
        e.printStackTrace
        System.exit(1)
    }
  }

  private def initRabbitMQ(config: Rabbitmq): Either[Throwable, (Connection, Channel)] =
    Either.catchNonFatal {
      val factory = new ConnectionFactory()
      factory.setUsername(config.username)
      factory.setPassword(config.password)
      factory.setVirtualHost(config.virtualHost)
      factory.setHost(config.host)
      factory.setPort(config.port)
      val connection = factory.newConnection()
      val channel    = connection.createChannel()
      (connection, channel)
    }

  private def initThreadPool(size: Option[Int]): ExecutionContext =
    size match {
      case Some(s) => ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(s))
      case None    => ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
    }

  private def shutdownHook(connection: Connection, channel: Channel) =
    new Thread() {
      override def run() {
        if (channel.isOpen) channel.close()
        if (connection.isOpen) connection.close()
      }
    }
}
