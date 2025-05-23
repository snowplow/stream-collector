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
package com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.containers

import cats.effect.{IO, Resource}
import com.dimafeng.testcontainers.GenericContainer
import com.snowplowanalytics.snowplow.collectors.scalastream.BuildInfo
import com.snowplowanalytics.snowplow.collectors.scalastream.it.CollectorContainer
import com.snowplowanalytics.snowplow.collectors.scalastream.it.utils._
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.wait.strategy.Wait

object Collector {

  val port = 8080
  val maxBytes = 10000

  def container(
    configPath: String,
    testName: String,
    streamGood: String,
    streamBad: String,
    createStreams: Boolean = true,
    additionalConfig: Map[String, String] = Map.empty
  ): Resource[IO, CollectorContainer] = {
    val container = GenericContainer(
      dockerImage = BuildInfo.dockerAlias,
      env = Map(
        "AWS_ACCESS_KEY_ID" -> "whatever",
        "AWS_SECRET_ACCESS_KEY" -> "whatever",
        "PORT" -> port.toString,
        "STREAM_GOOD" -> streamGood,
        "STREAM_BAD" -> streamBad,
        "REGION" -> Localstack.region,
        "KINESIS_ENDPOINT" -> Localstack.privateEndpoint,
        "MAX_BYTES" -> maxBytes.toString,
        "JDK_JAVA_OPTIONS" -> "-Dlogger.level.com.snowplowanalytics.snowplow.collectors.scalastream.sinks.KinesisSink=WARN",
        "HTTP4S_BACKEND" -> "BLAZE"
      ) ++ additionalConfig,
      exposedPorts = Seq(port),
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
      ),
      waitStrategy = Wait.forLogMessage(s".*Service bound to address.*", 1)
    )
    container.container.withNetwork(Localstack.network)

    val create = if(createStreams) Localstack.createStreams(List(streamGood, streamBad)) else IO.unit

    Resource.make(
      create *>
        IO(startContainerWithLogs(container.container, testName))
          .map(c => CollectorContainer(c, c.getHost, c.getMappedPort(Collector.port)))
    )(
      c => IO(c.container.stop())
    )
  }
}
