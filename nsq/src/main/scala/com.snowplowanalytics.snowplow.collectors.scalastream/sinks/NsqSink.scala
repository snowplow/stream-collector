/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
  */
package com.snowplowanalytics.snowplow.collectors.scalastream
package sinks

import java.util.concurrent.TimeoutException

import scala.collection.JavaConverters._

import cats.effect.{Resource, Sync}
import cats.implicits._

import com.snowplowanalytics.client.nsq.NSQProducer
import com.snowplowanalytics.snowplow.collector.core.{Sink}
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
        Sync[F].delay(healthStatus = false)
    } *> Sync[F].delay(healthStatus = true)

  def shutdown(): Unit =
    producer.shutdown()
}

object NsqSink {

  def create[F[_]: Sync](
    nsqConfig: NsqSinkConfig,
    topicName: String
  ): Resource[F, NsqSink[F]] =
    Resource.make(
      Sync[F].delay(
        // MaxBytes is never used but is required by the sink interface definition,
        // So just pass any int val in.
        new NsqSink(0, nsqConfig, topicName)
      )
    )(sink => Sync[F].delay(sink.shutdown()))
}
