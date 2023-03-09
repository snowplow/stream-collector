/*
 * Copyright (c) 2022-2022 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, and
 * you may not use this file except in compliance with the Apache License
 * Version 2.0.  You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Apache License Version 2.0 is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream.it.pubsub

import scala.concurrent.ExecutionContext

import org.testcontainers.containers.{BindMode, GenericContainer => JGenericContainer, Network}
import org.testcontainers.containers.wait.strategy.Wait

import com.dimafeng.testcontainers.GenericContainer

import cats.effect.{IO, Resource, Timer}

import com.snowplowanalytics.snowplow.collectors.scalastream.generated.ProjectMetadata

import com.snowplowanalytics.snowplow.collectors.scalastream.it.utils._

object Containers {

  private val executionContext: ExecutionContext = ExecutionContext.global
  implicit val ioTimer: Timer[IO] = IO.timer(executionContext)

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
    envs: Map[String, String] = Map.empty[String, String]
  ): Resource[IO, JGenericContainer[_]] = {
    val container = GenericContainer(
      dockerImage = s"snowplow/scala-stream-collector-pubsub:${ProjectMetadata.dockerTag}",
      env = Map("PUBSUB_EMULATOR_HOST" -> s"pubsub-emulator:$emulatorPort") ++ envs,
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
      ,waitStrategy = Wait.forLogMessage(s".*REST interface bound to.*", 1)
    )
    container.container.withNetwork(network)
    Resource.make (
      IO(startContainerWithLogs(container.container, testName))
    )(
      e => IO(e.stop())
    )
  }

  def startEmulator(): Unit = {
    pubSubEmulator.start()
    PubSub.createTopicsAndSubscriptions(
      projectId,
      emulatorHost,
      emulatorHostPort,
      List(topicGood, topicBad)
    )
  }

  def stopEmulator(): Unit = pubSubEmulator.stop()
}
