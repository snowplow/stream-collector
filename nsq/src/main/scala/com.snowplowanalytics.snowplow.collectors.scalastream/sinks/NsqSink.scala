/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This software is made available by Snowplow Analytics, Ltd.,
  * under the terms of the Snowplow Limited Use License Agreement, Version 1.0
  * located at https://docs.snowplow.io/limited-use-license-1.1
  * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
  * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
  */
package com.snowplowanalytics.snowplow.collectors.scalastream
package sinks

import java.util.concurrent.TimeoutException
import scala.jdk.CollectionConverters._
import cats.effect.{Resource, Sync}
import cats.implicits._
import com.snowplowanalytics.client.nsq.NSQProducer
import com.snowplowanalytics.snowplow.collector.core.{Config, Sink}
import com.snowplowanalytics.client.nsq.exceptions.NSQException

/**
  * NSQ Sink for the Scala Stream Collector
  * @param nsqConfig Configuration for Nsq
  * @param topicName Nsq topic name
  */
class NsqSink[F[_]: Sync] private (
  val maxBytes: Int,
  nsqConfig: NsqSinkConfig,
  topicName: String
) extends Sink[F] {

  @volatile private var healthStatus = true

  override def isHealthy: F[Boolean] = Sync[F].pure(healthStatus)

  private val producer = new NSQProducer().addAddress(nsqConfig.host, nsqConfig.port).start()

  /**
    * Store raw events to the topic
    * @param events The list of events to send
    * @param key The partition key (unused)
    */
  override def storeRawEvents(events: List[Array[Byte]], key: String): F[Unit] =
    Sync[F].blocking(producer.produceMulti(topicName, events.asJava)).onError {
      case _: NSQException | _: TimeoutException =>
        setHealthStatus(false)
    } *> setHealthStatus(true)

  def shutdown(): Unit =
    producer.shutdown()

  private def setHealthStatus(status: Boolean): F[Unit] = Sync[F].delay {
    healthStatus = status
  }
}

object NsqSink {

  def create[F[_]: Sync](
    nsqConfig: Config.Sink[NsqSinkConfig]
  ): Resource[F, NsqSink[F]] =
    Resource.make(
      Sync[F].delay(
        new NsqSink(nsqConfig.config.maxBytes, nsqConfig.config, nsqConfig.name)
      )
    )(sink => Sync[F].delay(sink.shutdown()))
}
