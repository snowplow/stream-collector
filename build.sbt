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
  javacOptions := Seq("-source", "11", "-target", "11"),
  resolvers ++= Dependencies.resolutionRepos
)

lazy val dockerSettings = Seq(
  Docker / maintainer := "Snowplow Analytics Ltd. <support@snowplowanalytics.com>",
  dockerBaseImage := "eclipse-temurin:11-jre-focal",
  Docker / daemonUser := "daemon",
  dockerRepository := Some("snowplow"),
  Docker / daemonUserUid := None,
  Docker / defaultLinuxInstallLocation := "/opt/snowplow"
)

lazy val dockerSettingsDistroless = Seq(
  Docker / maintainer := "Snowplow Analytics Ltd. <support@snowplowanalytics.com>",
  dockerBaseImage := "gcr.io/distroless/java11-debian11:nonroot",
  Docker / daemonUser := "nonroot",
  Docker / daemonGroup := "nonroot",
  dockerRepository := Some("snowplow"),
  Docker / daemonUserUid := None,
  Docker / defaultLinuxInstallLocation := "/opt/snowplow",
  dockerEntrypoint := Seq("java", "-jar",s"/opt/snowplow/lib/${(packageJavaLauncherJar / artifactPath).value.getName}"),
  dockerPermissionStrategy := DockerPermissionStrategy.CopyChown
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

lazy val kinesisSettings = 
  allSettings ++ buildInfoSettings ++ Seq(
    moduleName := "snowplow-stream-collector-kinesis",
    Docker / packageName := "scala-stream-collector-kinesis",
    libraryDependencies ++= Seq(
      Dependencies.Libraries.kinesis,
      Dependencies.Libraries.sts,
      Dependencies.Libraries.cbor,
      Dependencies.Libraries.sqs
    )
  )

lazy val kinesis = project
  .settings(kinesisSettings ++ dockerSettings)
  .enablePlugins(JavaAppPackaging, LauncherJarPlugin, DockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")

lazy val kinesisDistroless = project
  .in(file("distroless/kinesis"))
  .settings(sourceDirectory := (kinesis / sourceDirectory).value)
  .settings(kinesisSettings ++ dockerSettingsDistroless)
  .enablePlugins(JavaAppPackaging, LauncherJarPlugin, DockerPlugin, BuildInfoPlugin)
  .dependsOn(kinesis % "test->test;compile->compile")

lazy val sqsSettings = 
  allSettings ++ buildInfoSettings ++ Seq(
    moduleName := "snowplow-stream-collector-sqs",
    Docker / packageName := "scala-stream-collector-sqs",
    libraryDependencies ++= Seq(
      Dependencies.Libraries.sqs,
      Dependencies.Libraries.sts,
      Dependencies.Libraries.cbor
    )
  )

lazy val sqs = project
  .settings(sqsSettings ++ dockerSettings)
  .enablePlugins(JavaAppPackaging, LauncherJarPlugin, DockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")

lazy val sqsDistroless = project
  .in(file("distroless/sqs"))
  .settings(sourceDirectory := (sqs / sourceDirectory).value)
  .settings(sqsSettings ++ dockerSettingsDistroless)
  .enablePlugins(JavaAppPackaging, LauncherJarPlugin, DockerPlugin, BuildInfoPlugin)
  .dependsOn(sqs % "test->test;compile->compile")

lazy val pubsubSettings = 
  allSettings ++ buildInfoSettings ++ Seq(
    moduleName := "snowplow-stream-collector-google-pubsub",
    Docker / packageName := "scala-stream-collector-pubsub",
    libraryDependencies ++= Seq(Dependencies.Libraries.pubsub)
  )

lazy val pubsub = project
  .settings(pubsubSettings ++ dockerSettings)
  .enablePlugins(JavaAppPackaging, LauncherJarPlugin, DockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")

lazy val pubsubDistroless = project
  .in(file("distroless/pubsub"))
  .settings(sourceDirectory := (pubsub / sourceDirectory).value)
  .settings(pubsubSettings ++ dockerSettingsDistroless)
  .enablePlugins(JavaAppPackaging, LauncherJarPlugin, DockerPlugin, BuildInfoPlugin)
  .dependsOn(pubsub % "test->test;compile->compile")

lazy val kafkaSettings = 
  allSettings ++ buildInfoSettings ++ Seq(
    moduleName := "snowplow-stream-collector-kafka",
    Docker / packageName := "scala-stream-collector-kafka",
    libraryDependencies ++= Seq(Dependencies.Libraries.kafkaClients)
  )

lazy val kafka = project
  .settings(kafkaSettings ++ dockerSettings)
  .enablePlugins(JavaAppPackaging, LauncherJarPlugin, DockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")

lazy val kafkaDistroless = project
  .in(file("distroless/kafka"))
  .settings(sourceDirectory := (kafka / sourceDirectory).value)
  .settings(kafkaSettings ++ dockerSettingsDistroless)
  .enablePlugins(JavaAppPackaging, LauncherJarPlugin, DockerPlugin, BuildInfoPlugin)
  .dependsOn(kafka % "test->test;compile->compile")

lazy val nsqSettings = 
  allSettings ++ buildInfoSettings ++ Seq(
    moduleName := "snowplow-stream-collector-nsq",
    Docker / packageName := "scala-stream-collector-nsq",
    libraryDependencies ++= Seq(
      Dependencies.Libraries.nsqClient,
      Dependencies.Libraries.jackson,
      Dependencies.Libraries.log4j
    )
  )

lazy val nsq = project
  .settings(nsqSettings ++ dockerSettings)
  .enablePlugins(JavaAppPackaging, LauncherJarPlugin, DockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")

lazy val nsqDistroless = project
  .in(file("distroless/nsq"))
  .settings(sourceDirectory := (nsq / sourceDirectory).value)
  .settings(nsqSettings ++ dockerSettingsDistroless)
  .enablePlugins(JavaAppPackaging, LauncherJarPlugin, DockerPlugin, BuildInfoPlugin)
  .dependsOn(nsq % "test->test;compile->compile")

lazy val stdoutSettings = 
  allSettings ++ buildInfoSettings ++ Seq(
    moduleName := "snowplow-stream-collector-stdout",
    Docker / packageName := "scala-stream-collector-stdout"
  )

lazy val stdout = project
  .settings(stdoutSettings ++ dockerSettings)
  .enablePlugins(JavaAppPackaging, LauncherJarPlugin, DockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")

lazy val stdoutDistroless = project
  .in(file("distroless/stdout"))
  .settings(sourceDirectory := (stdout / sourceDirectory).value)
  .settings(stdoutSettings ++ dockerSettingsDistroless)
  .enablePlugins(JavaAppPackaging, LauncherJarPlugin, DockerPlugin, BuildInfoPlugin)
  .dependsOn(stdout % "test->test;compile->compile")
