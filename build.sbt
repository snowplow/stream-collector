/*
 * Copyright (c) 2013-2021 Snowplow Analytics Ltd. All rights reserved.
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
import com.typesafe.sbt.packager.docker._

lazy val commonDependencies = Seq(
  // Java
  Dependencies.Libraries.thrift,
  Dependencies.Libraries.jodaTime,
  Dependencies.Libraries.slf4j,
  Dependencies.Libraries.log4jOverSlf4j,
  Dependencies.Libraries.config,
  Dependencies.Libraries.prometheus,
  Dependencies.Libraries.prometheusCommon,
  // Scala
  Dependencies.Libraries.scopt,
  Dependencies.Libraries.akkaStream,
  Dependencies.Libraries.akkaHttp,
  Dependencies.Libraries.akkaStream,
  Dependencies.Libraries.akkaSlf4j,
  Dependencies.Libraries.badRows,
  Dependencies.Libraries.collectorPayload,
  Dependencies.Libraries.pureconfig,
  // Scala (test)
  Dependencies.Libraries.akkaTestkit,
  Dependencies.Libraries.akkaHttpTestkit,
  Dependencies.Libraries.akkaStreamTestkit,
  Dependencies.Libraries.specs2
)

lazy val commonExclusions = Seq(
  "org.apache.tomcat.embed" % "tomcat-embed-core" // exclude for security vulnerabilities introduced by libthrift
)

lazy val buildSettings = Seq(
  organization := "com.snowplowanalytics",
  name := "snowplow-stream-collector",
  version := "2.3.0",
  description := "Scala Stream Collector for Snowplow raw events",
  scalaVersion := "2.12.10",
  javacOptions := Seq("-source", "11", "-target", "11"),
  resolvers ++= Dependencies.resolutionRepos
)

lazy val dockerSettings = Seq(
  Docker / maintainer := "Snowplow Analytics Ltd. <support@snowplowanalytics.com>",
  dockerBaseImage := "snowplow/base-debian:0.2.2",
  Docker / daemonUser := "snowplow",
  dockerUpdateLatest := true
)

lazy val allSettings = buildSettings ++
  BuildSettings.sbtAssemblySettings ++
  BuildSettings.formatting ++
  Seq(libraryDependencies ++= commonDependencies) ++
  Seq(excludeDependencies ++= commonExclusions) ++
  dockerSettings

lazy val root = project.in(file(".")).settings(buildSettings).aggregate(core, kinesis, pubsub, kafka, nsq, stdout)

lazy val core = project
  .settings(moduleName := "snowplow-stream-collector-core")
  .settings(buildSettings ++ BuildSettings.sbtAssemblySettings)
  .settings(libraryDependencies ++= commonDependencies)
  .settings(excludeDependencies ++= commonExclusions)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    buildInfoKeys := Seq[BuildInfoKey](
      organization,
      name,
      version,
      "shortName" -> "ssc",
      scalaVersion
    ),
    buildInfoPackage := "com.snowplowanalytics.snowplow.collectors.scalastream.generated"
  )

lazy val kinesis = project
  .settings(moduleName := "snowplow-stream-collector-kinesis")
  .settings(allSettings)
  .settings(Docker / packageName := "snowplow/scala-stream-collector-kinesis")
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.Libraries.kinesis,
      Dependencies.Libraries.cbor,
      Dependencies.Libraries.sqs
    )
  )
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(core)

lazy val sqs = project
  .settings(moduleName := "snowplow-stream-collector-sqs")
  .settings(allSettings)
  .settings(Docker / packageName := "snowplow/scala-stream-collector-sqs")
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.Libraries.sqs,
      Dependencies.Libraries.cbor
    )
  )
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(core)

lazy val pubsub = project
  .settings(moduleName := "snowplow-stream-collector-google-pubsub")
  .settings(allSettings)
  .settings(Docker / packageName := "snowplow/scala-stream-collector-pubsub")
  .settings(libraryDependencies ++= Seq(Dependencies.Libraries.pubsub))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(core)

lazy val kafka = project
  .settings(moduleName := "snowplow-stream-collector-kafka")
  .settings(allSettings)
  .settings(Docker / packageName := "snowplow/scala-stream-collector-kafka")
  .settings(libraryDependencies ++= Seq(Dependencies.Libraries.kafkaClients))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(core)

lazy val nsq = project
  .settings(moduleName := "snowplow-stream-collector-nsq")
  .settings(allSettings)
  .settings(Docker / packageName := "snowplow/scala-stream-collector-nsq")
  .settings(libraryDependencies ++= Seq(
    Dependencies.Libraries.nsqClient,
    Dependencies.Libraries.jackson
  ))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(core)

lazy val stdout = project
  .settings(moduleName := "snowplow-stream-collector-stdout")
  .settings(allSettings)
  .settings(Docker / packageName := "snowplow/scala-stream-collector-stdout")
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(core)
