/*
 * Copyright (c) 2023-2023 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.collectors.scalastream.it.kinesis.containers

import org.testcontainers.containers.{BindMode, GenericContainer => JGenericContainer}
import org.testcontainers.containers.wait.strategy.Wait

import com.dimafeng.testcontainers.GenericContainer

import cats.effect.{IO, Resource}

import com.snowplowanalytics.snowplow.collectors.scalastream.generated.ProjectMetadata

import com.snowplowanalytics.snowplow.collectors.scalastream.it.utils._

object Collector {

  val port = 8080
  val maxBytes = 10000

  def container(
    configPath: String,
    testName: String,
    streamGood: String,
    streamBad: String
  ): Resource[IO, JGenericContainer[_]] = {
    val container = GenericContainer(
      dockerImage = s"snowplow/scala-stream-collector-kinesis:${ProjectMetadata.version}",
      env = Map(
        "AWS_ACCESS_KEY_ID" -> "whatever",
        "AWS_SECRET_ACCESS_KEY" -> "whatever",
        "PORT" -> port.toString,
        "STREAM_GOOD" -> streamGood,
        "STREAM_BAD" -> streamBad,
        "REGION" -> Localstack.region,
        "KINESIS_ENDPOINT" -> Localstack.privateEndpoint,
        "MAX_BYTES" -> maxBytes.toString()
      ),
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
      waitStrategy = Wait.forLogMessage(s".*REST interface bound to.*", 1)
    )
    container.container.withNetwork(Localstack.network)
    Resource.make (
      Localstack.createStreams(List(streamGood, streamBad)) *>
        IO(startContainerWithLogs(container.container, testName))
    )(
      e => IO(e.stop())
    )
  }
}
