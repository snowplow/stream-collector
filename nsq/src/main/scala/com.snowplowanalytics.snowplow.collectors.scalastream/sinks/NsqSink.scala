/*
 * Copyright (c) 2013-2023 Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache
 * License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 *
 * See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream
package sinks

import java.util.concurrent.TimeoutException
import scala.collection.JavaConverters._
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
        Sync[F].delay(healthStatus = false)
    } *> Sync[F].delay(healthStatus = true)

  def shutdown(): Unit =
    producer.shutdown()
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
