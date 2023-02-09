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

import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait

import cats.implicits._

import cats.effect.IO

import com.dimafeng.testcontainers.GenericContainer

object Localstack {

  val region = "eu-central-1"
  val host = "localhost"
  val alias = "localstack"
  val privatePort = 4566

  val network = Network.newNetwork()

  val localstack = {
    val container = GenericContainer(
      dockerImage = "localstack/localstack-light:1.3.0",
      env = Map(
        "AWS_ACCESS_KEY_ID" -> "unused",
        "AWS_SECRET_ACCESS_KEY" -> "unused"
      ),
      waitStrategy = Wait.forLogMessage(".*Ready.*", 1),
      exposedPorts = Seq(privatePort)
    )
    container.underlyingUnsafeContainer.withNetwork(network)
    container.underlyingUnsafeContainer.withNetworkAliases(alias)
    container.container
  }

  def start() = localstack.start()

  def stop() = localstack.stop()

  def publicPort = localstack.getMappedPort(privatePort)

  def privateEndpoint: String =
    s"http://$alias:$privatePort"

  def publicEndpoint: String =
    s"http://$host:$publicPort"

  def createStreams(
    streams: List[String]
  ): IO[Unit] =
    streams
      .traverse_ { s =>
        IO(
          localstack.execInContainer(
            "aws",
            s"--endpoint-url=http://$host:$privatePort",
            "kinesis",
            "create-stream",
            "--stream-name",
            s,
            "--shard-count",
            "1",
            "--region",
            region
          )
        )
          .flatMap {
            case res if res.getExitCode() != 0 =>
              IO.raiseError(new RuntimeException(s"Problem when creating stream $s [${res.getStderr()}] [${res.getStdout()}]"))
            case _ => IO(println(s"Stream $s created"))
          }
      }
}
