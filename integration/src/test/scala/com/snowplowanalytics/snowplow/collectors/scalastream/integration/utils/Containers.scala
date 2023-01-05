/*
 * Copyright (c) 2013-2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.collectors.scalastream.integration.utils

import cats.effect.{Resource, Sync}
import com.dimafeng.testcontainers.GenericContainer
import com.snowplowanalytics.snowplow.collectors.scalastream.generated.ProjectMetadata
import com.snowplowanalytics.snowplow.collectors.scalastream.integration.CollectorConfig
import org.slf4j.LoggerFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.{BindMode, Network, GenericContainer => JGenericContainer}

object Containers {
  val LocalstackExposedPort = 4566
  val CollectorExposedPort  = 12345
  val CollectorInterface    = "0.0.0.0"

  private val network = Network.newNetwork()

  def localstack: JGenericContainer[_] = {
    val container = GenericContainer(
      dockerImage  = "localstack/localstack-light:1.2.0",
      exposedPorts = Seq(LocalstackExposedPort),
      env          = Map("USE_SSL" -> "1"),
      waitStrategy = Wait.forLogMessage(".*AWS kinesis.CreateStream.*", 2),
      fileSystemBind = Seq(
        // This exposes any data in the mocked Kinesis streams via the local filesystem.
        // Could be useful to read data directly, rather than needing a Kinesis clients.
        // Delete if not in use.
        GenericContainer.FileSystemBind(
          "./.localstack",
          "/var/lib/localstack",
          BindMode.READ_WRITE
        ),
        GenericContainer.FileSystemBind(
          "integration/src/test/resources/localstack",
          "/docker-entrypoint-initaws.d",
          BindMode.READ_ONLY
        )
      )
    )
    container.underlyingUnsafeContainer.withNetwork(network)
    container.underlyingUnsafeContainer.withNetworkAliases("localstack")
    container.container
  }

  def collector(
    flavour: String,
    testConfig: CollectorConfig
  ): JGenericContainer[_] = {
    val version = ProjectMetadata.version
    val image   = s"snowplow/scala-stream-collector-$flavour:$version"
    val container = GenericContainer(
      dockerImage  = image,
      exposedPorts = Seq(CollectorExposedPort),
      env          = Map("AWS_ACCESS_KEY_ID" -> "test", "AWS_SECRET_KEY" -> "test") ++ testConfig,
      command      = Seq("--config", s"/snowplow/config/collector.hocon"),
      waitStrategy = Wait.forLogMessage(".*REST interface bound to.*", 1),
      fileSystemBind = Seq(
        GenericContainer.FileSystemBind(
          s"integration/src/test/resources/collector_config/collector.hocon",
          "/snowplow/config/collector.hocon",
          BindMode.READ_ONLY
        )
      )
    )
    container.underlyingUnsafeContainer.withNetwork(network)
    container.underlyingUnsafeContainer.withNetworkAliases("collector")
    container.container
  }

  def mkContainer[F[_]: Sync](
    container: JGenericContainer[_],
    loggerName: Option[String] = None
  ): Resource[F, JGenericContainer[_]] =
    Resource.make(Sync[F].delay(start(container, loggerName)))(c => Sync[F].delay(stop(c)))

  def start(container: JGenericContainer[_], loggerName: Option[String]): JGenericContainer[_] = {
    container.start()

    loggerName.foreach { ln =>
      val logger = LoggerFactory.getLogger(ln)
      val logs   = new Slf4jLogConsumer(logger)
      container.followOutput(logs)
    }

    container
  }

  def stop(container: JGenericContainer[_]): Unit = container.stop()

  def getExposedPort(container: JGenericContainer[_], targetPort: Int): Int = container.getMappedPort(targetPort)
}
