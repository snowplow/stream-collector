/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
  */
package com.snowplowanalytics.snowplow.collectors.scalastream.it.kafka

import cats.effect._
import com.dimafeng.testcontainers.{FixedHostPortGenericContainer, GenericContainer}
import com.snowplowanalytics.snowplow.collectors.scalastream.BuildInfo
import com.snowplowanalytics.snowplow.collectors.scalastream.it.CollectorContainer
import com.snowplowanalytics.snowplow.collectors.scalastream.it.utils._
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.{BindMode, Network, GenericContainer => JGenericContainer}

object Containers {

  val zookeeperContainerName = "zookeeper"
  val zookeeperPort = 2181
  val brokerContainerName = "broker"
  val brokerExternalPort = 9092
  val brokerInternalPort = 29092

  def createContainers(
    goodTopic: String,
    badTopic: String,
    maxBytes: Int
  ): Resource[IO, CollectorContainer] =
    for {
      network <- network()
      _ <- zookeeper(network)
      _ <- kafka(network)
      c <- collectorKafka(network, goodTopic, badTopic, maxBytes)
    } yield c

  private def network(): Resource[IO, Network] =
    Resource.make(IO(Network.newNetwork()))(n => IO(n.close()))

  private def kafka(
    network: Network
  ): Resource[IO, JGenericContainer[_]] =
    Resource.make(
      IO {
        val container = FixedHostPortGenericContainer(
          imageName = "confluentinc/cp-kafka:7.0.1",
          env = Map(
            "KAFKA_BROKER_ID" -> "1",
            "KAFKA_ZOOKEEPER_CONNECT" -> s"$zookeeperContainerName:$zookeeperPort",
            "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP" -> "PLAINTEXT:PLAINTEXT,PLAINTEXT_INTERNAL:PLAINTEXT",
            "KAFKA_ADVERTISED_LISTENERS" -> s"PLAINTEXT://localhost:$brokerExternalPort,PLAINTEXT_INTERNAL://$brokerContainerName:$brokerInternalPort",
            "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR" -> "1",
            "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR" -> "1",
            "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR" -> "1"
          ),
          exposedPorts = List(brokerExternalPort, brokerInternalPort),
          exposedHostPort = brokerExternalPort,
          exposedContainerPort = brokerExternalPort
        )
        container.container.withNetwork(network)
        container.container.withNetworkAliases(brokerContainerName)
        container.start()
        container.container
      }
    )(e => IO(e.stop()))

  private def zookeeper(
    network: Network,
  ): Resource[IO, JGenericContainer[_]] =
    Resource.make(
      IO {
        val container = GenericContainer(
          dockerImage = "confluentinc/cp-zookeeper:7.0.1",
          env = Map(
            "ZOOKEEPER_CLIENT_PORT" -> zookeeperPort.toString,
            "ZOOKEEPER_TICK_TIME" -> "2000"
          ),
          exposedPorts = List(zookeeperPort)
        )
        container.container.withNetwork(network)
        container.container.withNetworkAliases(zookeeperContainerName)
        container.start()
        container.container
      }
    )(e => IO(e.stop()))

  def collectorKafka(
    network: Network,
    goodTopic: String,
    badTopic: String,
    maxBytes: Int
  ): Resource[IO, CollectorContainer] = {
    Resource.make(
        IO {
          val collectorPort = 8080
          val container = GenericContainer(
            dockerImage = BuildInfo.dockerAlias,
            env = Map(
              "PORT" -> collectorPort.toString,
              "BROKER" -> s"$brokerContainerName:$brokerInternalPort",
              "TOPIC_GOOD" -> goodTopic,
              "TOPIC_BAD" -> badTopic,
              "MAX_BYTES" -> maxBytes.toString
            ),
            exposedPorts = Seq(collectorPort),
            fileSystemBind = Seq(
              GenericContainer.FileSystemBind(
                "kafka/src/it/resources/collector.hocon",
                "/snowplow/config/collector.hocon",
                BindMode.READ_ONLY
              )
            ),
            command = Seq(
              "--config",
              "/snowplow/config/collector.hocon"
            ),
            waitStrategy = Wait.forLogMessage(s".*Service bound to address.*", 1)
          )
          container.container.withNetwork(network)
          val c = startContainerWithLogs(container.container, "collector")
          CollectorContainer(c, c.getHost, c.getMappedPort(collectorPort))
        }
    )(c => IO(c.container.stop()))
  }
}
