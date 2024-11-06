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
package sinks

import cats.implicits._
import cats.effect._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}

import com.snowplowanalytics.snowplow.collector.core.{Config, Sink}

import scala.jdk.CollectionConverters._

/**
  * Kafka Sink for the Scala Stream Collector
  */
class KafkaSink[F[_]: Async: Logger](
  val maxBytes: Int,
  isHealthyState: Ref[F, Boolean],
  kafkaProducer: KafkaProducer[String, Array[Byte]],
  topicName: String
) extends Sink[F] {

  override def isHealthy: F[Boolean] = isHealthyState.get

  /**
    * Store raw events to the topic
    *
    * @param events The list of events to send
    * @param key The partition key to use
    */
  override def storeRawEvents(events: List[Array[Byte]], key: String): F[Unit] =
    Logger[F].debug(s"Writing ${events.size} Thrift records to Kafka topic $topicName at key $key") *>
      events.traverse_ { e =>
        def go: F[Unit] =
          Async[F]
            .async_[Unit] { cb =>
              val record = new ProducerRecord(topicName, key, e)
              kafkaProducer.send(record, callback(cb))
              ()
            }
            .handleErrorWith { e =>
              handlePublishError(e) >> go
            }
        go
      } *> isHealthyState.set(true)

  private def callback(asyncCallback: Either[Throwable, Unit] => Unit): Callback =
    new Callback {
      def onCompletion(metadata: RecordMetadata, exception: Exception): Unit =
        Option(exception) match {
          case Some(e) => asyncCallback(Left(e))
          case None    => asyncCallback(Right(()))
        }
    }

  private def handlePublishError(error: Throwable): F[Unit] =
    isHealthyState.set(false) *> Logger[F].error(s"Publishing to Kafka failed with message ${error.getMessage}")
}

object KafkaSink {

  implicit private def unsafeLogger[F[_]: Sync]: Logger[F] =
    Slf4jLogger.getLogger[F]

  def create[F[_]: Async](
    sinkConfig: Config.Sink[KafkaSinkConfig],
    authCallbackClass: String
  ): Resource[F, KafkaSink[F]] =
    for {
      isHealthyState <- Resource.eval(Ref.of[F, Boolean](false))
      kafkaProducer  <- createProducer(sinkConfig.config, sinkConfig.buffer, authCallbackClass)
    } yield new KafkaSink(
      sinkConfig.config.maxBytes,
      isHealthyState,
      kafkaProducer,
      sinkConfig.name
    )

  /**
    * Creates a new Kafka Producer with the given
    * configuration options
    *
    * @return a new Kafka Producer
    */
  private def createProducer[F[_]: Async](
    kafkaConfig: KafkaSinkConfig,
    bufferConfig: Config.Buffer,
    authCallbackClass: String
  ): Resource[F, KafkaProducer[String, Array[Byte]]] = {
    val props = Map(
      "bootstrap.servers"                 -> kafkaConfig.brokers,
      "acks"                              -> "all",
      "retries"                           -> kafkaConfig.retries.toString,
      "linger.ms"                         -> bufferConfig.timeLimit.toString,
      "key.serializer"                    -> "org.apache.kafka.common.serialization.StringSerializer",
      "value.serializer"                  -> "org.apache.kafka.common.serialization.ByteArraySerializer",
      "sasl.login.callback.handler.class" -> authCallbackClass
    ) ++ kafkaConfig.producerConf.getOrElse(Map.empty) + ("buffer.memory" -> Long.MaxValue.toString)

    val make = Sync[F].delay {
      new KafkaProducer[String, Array[Byte]]((props: Map[String, AnyRef]).asJava)
    }
    Resource.make(make)(p => Sync[F].blocking(p.close))
  }
}
