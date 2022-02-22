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
import com.typesafe.sbt.packager.docker._
import sbtbuildinfo.BuildInfoPlugin.autoImport.buildInfoPackage

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
  Dependencies.Libraries.trackerCore,
  Dependencies.Libraries.trackerEmitterId,
  // Scala (test)
  Dependencies.Libraries.akkaTestkit,
  Dependencies.Libraries.akkaHttpTestkit,
  Dependencies.Libraries.akkaStreamTestkit,
  Dependencies.Libraries.specs2
)

lazy val commonExclusions = Seq(
  "org.apache.tomcat.embed" % "tomcat-embed-core", // exclude for security vulnerabilities introduced by libthrift
  // Avoid duplicate .proto files brought in by akka and google-cloud-pubsub.
  // We don't need any akka serializers because collector runs in a single JVM.
  "com.typesafe.akka" % "akka-protobuf-v3_2.12"
)

lazy val buildInfoSettings = Seq(
  buildInfoPackage := "com.snowplowanalytics.snowplow.collectors.scalastream.generated",
  buildInfoKeys := Seq[BuildInfoKey](organization, moduleName, name, version, "shortName" -> "ssc", scalaVersion)
)

lazy val buildSettings = Seq(
  organization := "com.snowplowanalytics",
  name := "snowplow-stream-collector",
  description := "Scala Stream Collector for Snowplow raw events",
  scalaVersion := "2.12.10",
  javacOptions := Seq("-source", "17", "-target", "17"),
  resolvers ++= Dependencies.resolutionRepos
)

lazy val dockerSettings = Seq(
  Docker / maintainer := "Snowplow Analytics Ltd. <support@snowplowanalytics.com>",
  dockerBaseImage := "eclipse-temurin:17-jre-focal",
  Docker / daemonUser := "daemon",
  dockerRepository := Some("snowplow"),
  Docker / daemonUserUid := None,
  Docker / defaultLinuxInstallLocation := "/opt/snowplow"
)

lazy val dynVerSettings = Seq(
  ThisBuild / dynverVTagPrefix := false, // Otherwise git tags required to have v-prefix
  ThisBuild / dynverSeparator := "-"     // to be compatible with docker
)

lazy val allSettings = buildSettings ++
  BuildSettings.sbtAssemblySettings ++
  BuildSettings.formatting ++
  Seq(libraryDependencies ++= commonDependencies) ++
  Seq(excludeDependencies ++= commonExclusions) ++
  dockerSettings ++
  dynVerSettings ++
  BuildSettings.addExampleConfToTestCp

lazy val root = project
  .in(file("."))
  .settings(buildSettings ++ dynVerSettings)
  .aggregate(core, kinesis, pubsub, kafka, nsq, stdout, sqs)

lazy val core = project
  .settings(moduleName := "snowplow-stream-collector-core")
  .settings(buildSettings ++ BuildSettings.sbtAssemblySettings)
  .settings(libraryDependencies ++= commonDependencies)
  .settings(excludeDependencies ++= commonExclusions)

lazy val kinesis = project
  .settings(moduleName := "snowplow-stream-collector-kinesis")
  .settings(allSettings)
  .settings(Docker / packageName := "scala-stream-collector-kinesis")
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.Libraries.kinesis,
      Dependencies.Libraries.sts,
      Dependencies.Libraries.cbor,
      Dependencies.Libraries.sqs
    )
  )
  .enablePlugins(JavaAppPackaging, DockerPlugin, BuildInfoPlugin)
  .settings(buildInfoSettings)
  .dependsOn(core % "test->test;compile->compile")

lazy val sqs = project
  .settings(moduleName := "snowplow-stream-collector-sqs")
  .settings(allSettings)
  .settings(Docker / packageName := "scala-stream-collector-sqs")
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.Libraries.sqs,
      Dependencies.Libraries.sts,
      Dependencies.Libraries.cbor
    )
  )
  .enablePlugins(JavaAppPackaging, DockerPlugin, BuildInfoPlugin)
  .settings(buildInfoSettings)
  .dependsOn(core % "test->test;compile->compile")

lazy val pubsub = project
  .settings(moduleName := "snowplow-stream-collector-google-pubsub")
  .settings(allSettings)
  .settings(Docker / packageName := "scala-stream-collector-pubsub")
  .settings(libraryDependencies ++= Seq(Dependencies.Libraries.pubsub))
  .enablePlugins(JavaAppPackaging, DockerPlugin, BuildInfoPlugin)
  .settings(buildInfoSettings)
  .dependsOn(core % "test->test;compile->compile")

lazy val kafka = project
  .settings(moduleName := "snowplow-stream-collector-kafka")
  .settings(allSettings)
  .settings(Docker / packageName := "scala-stream-collector-kafka")
  .settings(libraryDependencies ++= Seq(Dependencies.Libraries.kafkaClients))
  .enablePlugins(JavaAppPackaging, DockerPlugin, BuildInfoPlugin)
  .settings(buildInfoSettings)
  .dependsOn(core % "test->test;compile->compile")

lazy val nsq = project
  .settings(moduleName := "snowplow-stream-collector-nsq")
  .settings(allSettings)
  .settings(Docker / packageName := "scala-stream-collector-nsq")
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.Libraries.nsqClient,
      Dependencies.Libraries.jackson,
      Dependencies.Libraries.log4j
    )
  )
  .enablePlugins(JavaAppPackaging, DockerPlugin, BuildInfoPlugin)
  .settings(buildInfoSettings)
  .dependsOn(core % "test->test;compile->compile")

lazy val stdout = project
  .settings(moduleName := "snowplow-stream-collector-stdout")
  .settings(allSettings)
  .settings(Docker / packageName := "scala-stream-collector-stdout")
  .enablePlugins(JavaAppPackaging, DockerPlugin, BuildInfoPlugin)
  .settings(buildInfoSettings)
  .dependsOn(core % "test->test;compile->compile")
