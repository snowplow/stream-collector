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

import org.testcontainers.containers.{BindMode, Network}
import org.testcontainers.containers.wait.strategy.Wait
import com.dimafeng.testcontainers.GenericContainer
import cats.effect.{IO, Resource}
import com.snowplowanalytics.snowplow.collectors.scalastream.BuildInfo
import com.snowplowanalytics.snowplow.collectors.scalastream.it.utils._
import com.snowplowanalytics.snowplow.collectors.scalastream.it.CollectorContainer

object Containers {

  val collectorPort = 8080
  val projectId = "google-project-id"
  val emulatorHost = "localhost"
  val emulatorPort = 8085
  lazy val emulatorHostPort = pubSubEmulator.getMappedPort(emulatorPort)
  val topicGood = "good"
  val topicBad = "bad"

  private val network = Network.newNetwork()

  private val pubSubEmulator = {
    val container = GenericContainer(
      dockerImage = "gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators",
      waitStrategy = Wait.forLogMessage(".*Server started.*", 1),
      exposedPorts = Seq(emulatorPort),
      command = Seq(
        "gcloud",
        "beta",
        "emulators",
        "pubsub",
        "start",
        s"--project=$projectId",
        s"--host-port=0.0.0.0:$emulatorPort"
      )
    )

    container.underlyingUnsafeContainer.withNetwork(network)
    container.underlyingUnsafeContainer.withNetworkAliases("pubsub-emulator")
    container.container
  }

  def collector(
    configPath: String,
    testName: String,
    topicGood: String,
    topicBad: String,
    createTopics: Boolean = true,
    envs: Map[String, String] = Map.empty[String, String]
  ): Resource[IO, CollectorContainer] = {
    val container = GenericContainer(
      dockerImage = BuildInfo.dockerAlias,
      env = Map(
        "PUBSUB_EMULATOR_HOST" -> s"pubsub-emulator:$emulatorPort",
        "PORT" -> collectorPort.toString,
        "TOPIC_GOOD" -> topicGood,
        "TOPIC_BAD" -> topicBad,
        "GOOGLE_PROJECT_ID" -> projectId,
        "MAX_BYTES" -> Integer.MAX_VALUE.toString,
        "JDK_JAVA_OPTIONS" -> "-Dlogger.level.com.snowplowanalytics.snowplow.collectors.scalastream.sinks.GooglePubSubSink=WARN",
        "HTTP4S_BACKEND" -> "BLAZE"
      ) ++ envs,
      exposedPorts = Seq(collectorPort),
      fileSystemBind = Seq(
        GenericContainer.FileSystemBind(
          configPath,
          "/snowplow/config/collector.hocon",
          BindMode.READ_ONLY
        )
      ),
      command = Seq(
        "--config",
        "/snowplow/config/collector.hocon"
      )
      ,waitStrategy = Wait.forLogMessage(s".*Service bound to address.*", 1)
    )
    container.container.withNetwork(network)

    val create =
      if(createTopics)
        PubSub.createTopicsAndSubscriptions(
          projectId,
          emulatorHost,
          emulatorHostPort,
          List(topicGood, topicBad)
        )
      else
        IO.unit

    Resource.make (
      create *>
        IO(startContainerWithLogs(container.container, testName))
          .map(c => CollectorContainer(c, c.getHost, c.getMappedPort(collectorPort)))
    )(
      c => IO(c.container.stop())
    )
  }

  def startEmulator(): Unit = pubSubEmulator.start()

  def stopEmulator(): Unit = pubSubEmulator.stop()
}
