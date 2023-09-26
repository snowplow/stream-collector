/*
 * Copyright (c) 2022-2023 Snowplow Analytics Ltd. All rights reserved.
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

package com.snowplowanalytics.snowplow.collectors.scalastream.it.nsq

import org.testcontainers.containers.{BindMode, Network}
import org.testcontainers.containers.wait.strategy.Wait
import com.dimafeng.testcontainers.{GenericContainer, FixedHostPortGenericContainer}
import cats.effect.{IO, Resource}
import com.snowplowanalytics.snowplow.collectors.scalastream.BuildInfo
import com.snowplowanalytics.snowplow.collectors.scalastream.it.utils._
import com.snowplowanalytics.snowplow.collectors.scalastream.it.CollectorContainer

object Containers {

  val collectorPort = 8080
  // val projectId = "google-project-id"
  // val emulatorHost = "localhost"
  // val emulatorPort = 8085
  // lazy val emulatorHostPort = pubSubEmulator.getMappedPort(emulatorPort)
  val topicGood = "good"
  val topicBad = "bad"

  private val network = Network.newNetwork()

  // private val pubSubEmulator = {
  //   val container = GenericContainer(
  //     dockerImage = "gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators",
  //     waitStrategy = Wait.forLogMessage(".*Server started.*", 1),
  //     exposedPorts = Seq(emulatorPort),
  //     command = Seq(
  //       "gcloud",
  //       "beta",
  //       "emulators",
  //       "pubsub",
  //       "start",
  //       s"--project=$projectId",
  //       s"--host-port=0.0.0.0:$emulatorPort"
  //     )
  //   )

  //   container.underlyingUnsafeContainer.withNetwork(network)
  //   container.underlyingUnsafeContainer.withNetworkAliases("pubsub-emulator")
  //   container.container
  // }

  def collector(
    configPath: String,
    testName: String,
    // topicGood: String,
    // topicBad: String,
    // createTopics: Boolean = true,
    envs: Map[String, String] = Map.empty[String, String]
  ): Resource[IO, CollectorContainer] = {
    val container = GenericContainer(
      dockerImage = BuildInfo.dockerAlias,
      env = Map(
        "PORT" -> collectorPort.toString,
        "JDK_JAVA_OPTIONS" -> "-Dorg.slf4j.simpleLogger.log.com.snowplowanalytics.snowplow.collectors.scalastream.sinks.NsqSink=warn",
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
      // if(createTopics)
      //   PubSub.createTopicsAndSubscriptions(
      //     projectId,
      //     emulatorHost,
      //     emulatorHostPort,
      //     List(topicGood, topicBad)
      //   )
      // else
        IO.unit

    Resource.make (
      create *>
        IO(startContainerWithLogs(container.container, testName))
          .map(c => CollectorContainer(c, c.getHost, c.getMappedPort(collectorPort)))
    )(
      c => IO(c.container.stop())
    )
  }

  // We loosely copy the test implementation from enrich, which requires two nsqd and nsqlookup addresses - explained here: https://github.com/snowplow/enrich/blob/b13099e51be7a253c115ab0e0f66c31824771e33/modules/nsq/src/it/scala/com/snowplowanalytics/snowplow/enrich/nsq/test/Containers.scala#L52-L60
  // This wasn't necessary for manual testing which used the http api to check results, so we can likely rationalise this, but I ran out of time in my attempts to do so here.
  private val nsqlookupd1 = {
    val container = FixedHostPortGenericContainer(
      imageName = "nsqio/nsq:latest",
      command = Seq(
            "/nsqlookupd",
            s"--broadcast-address=nsqlookupd1",
            s"--http-address=0.0.0.0:4161",
            s"--tcp-address=0.0.0.0:4160",
          ),
      exposedPorts = List(4161, 4160),
      exposedContainerPort = 4161,
      exposedHostPort = 4161
    )
    container.container.withFixedExposedPort(4160, 4160)
    container.container.withNetwork(network)
    container.container.withNetworkAliases("nsqlookupd1")
    container.container
  }

   private val nsqlookupd2 = {
    val container = FixedHostPortGenericContainer(
      imageName = "nsqio/nsq:latest",
      command = Seq(
            "/nsqlookupd",
            s"--broadcast-address=nsqlookupd2",
            s"--http-address=0.0.0.0:4261",
            s"--tcp-address=0.0.0.0:4260",
          ),
      exposedPorts = List(4261, 4260),
      exposedContainerPort = 4261,
      exposedHostPort = 4261
    )
    container.container.withFixedExposedPort(4260, 4260)
    container.container.withNetwork(network)
    container.container.withNetworkAliases("nsqlookupd2")
    container.container
  }


  private val nsqd1 = {
    val container = FixedHostPortGenericContainer(
          imageName = "nsqio/nsq:latest",
          command = Seq(
            "/nsqd",
            s"--broadcast-address=nsqd",
            s"--broadcast-http-port=4151",
            s"--broadcast-tcp-port=4150",
            s"--http-address=0.0.0.0:4151",
            s"--tcp-address=0.0.0.0:4150",
            s"--lookupd-tcp-address=nsqlookupd1:4160"
          ),
          exposedPorts = List(4150, 4151),
          exposedContainerPort = 4151,
          exposedHostPort = 4151
        )
        container.container.withFixedExposedPort(4150, 4150)
        container.container.withNetwork(network)
        container.container.withNetworkAliases("nsqd")
        container.container
  }

    private val nsqd2 = {
    val container = FixedHostPortGenericContainer(
          imageName = "nsqio/nsq:latest",
          command = Seq(
            "/nsqd",
            s"--broadcast-address=127.0.0.1",
            s"--broadcast-http-port=4251",
            s"--broadcast-tcp-port=4250",
            s"--http-address=0.0.0.0:4251",
            s"--tcp-address=0.0.0.0:4250",
            s"--lookupd-tcp-address=nsqlookupd2:4260"
          ),
          exposedPorts = List(4250, 4251),
          exposedContainerPort = 4251,
          exposedHostPort = 4251
        )
        container.container.withFixedExposedPort(4250, 4250)
        container.container.withNetwork(network)
        container.container.withNetworkAliases("nsqd2")
        container.container
  }

  private val nsqTonsqGood = {
    val container = GenericContainer(
          dockerImage = "nsqio/nsq:latest",
          command = Seq(
            "/nsq_to_nsq",
            s"--nsqd-tcp-address=nsqd1:4150",
            s"--topic=good",
            s"--destination-nsqd-tcp-address=nsqd2:4250",
            s"--destination-topic=good",
          ),
        )
        container.container.withNetwork(network)
        container.container
  }

  private val nsqTonsqBad = {
    val container = GenericContainer(
          dockerImage = "nsqio/nsq:latest",
          command = Seq(
            "/nsq_to_nsq",
            s"--nsqd-tcp-address=nsqd1:4150",
            s"--topic=bad",
            s"--destination-nsqd-tcp-address=nsqd2:4250",
            s"--destination-topic=bad",
          ),
        )
        container.container.withNetwork(network)
        container.container
  }

  def startEmulator(): Unit = {
    nsqlookupd1.start()
    nsqlookupd2.start()
    nsqd1.start()
    nsqd2.start()
    nsqTonsqGood.start()
    nsqTonsqBad.start()
  }

  def stopEmulator(): Unit = {
    nsqlookupd1.stop()
    nsqlookupd2.stop()
    nsqd1.stop()
    nsqd2.stop()
    nsqTonsqGood.stop()
    nsqTonsqBad.stop()
  }
}
