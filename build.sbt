/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Snowplow Community License Version 1.0,
 * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
 * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
 */
import com.typesafe.sbt.packager.docker._
import sbtbuildinfo.BuildInfoPlugin.autoImport._

lazy val commonDependencies = Seq(
  // Java
  Dependencies.Libraries.thrift,
  Dependencies.Libraries.jodaTime,
  Dependencies.Libraries.slf4j,
  Dependencies.Libraries.log4jOverSlf4j,
  Dependencies.Libraries.config,
  // Scala
  Dependencies.Libraries.scopt,
  Dependencies.Libraries.akkaStream,
  Dependencies.Libraries.akkaHttp,
  Dependencies.Libraries.akkaStream,
  Dependencies.Libraries.akkaSlf4j,
  Dependencies.Libraries.akkaHttpMetrics,
  Dependencies.Libraries.jnrUnixsocket,
  Dependencies.Libraries.badRows,
  Dependencies.Libraries.collectorPayload,
  Dependencies.Libraries.pureconfig,
  Dependencies.Libraries.trackerCore,
  Dependencies.Libraries.trackerEmitterId,
  // Unit tests
  Dependencies.Libraries.akkaTestkit,
  Dependencies.Libraries.akkaHttpTestkit,
  Dependencies.Libraries.akkaStreamTestkit,
  Dependencies.Libraries.specs2,
  // Integration tests
  Dependencies.Libraries.testcontainersIt,
  Dependencies.Libraries.http4sClientIt,
  Dependencies.Libraries.catsRetryIt
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

// Make package (build) metadata available within source code for integration tests.
lazy val scalifiedSettings = Seq(
  IntegrationTest / sourceGenerators += Def.task {
    val file = (IntegrationTest / sourceManaged).value / "settings.scala"
    IO.write(
      file,
      """package %s
        |object ProjectMetadata {
        |  val organization = "%s"
        |  val name = "%s"
        |  val version = "%s"
        |  val dockerTag = "%s"
        |}
        |"""
        .stripMargin
        .format(
          buildInfoPackage.value,
          organization.value,
          name.value,
          version.value,
          dockerAlias.value.tag.get
        )
    )
    Seq(file)
  }.taskValue
)

lazy val buildSettings = Seq(
  organization := "com.snowplowanalytics",
  name := "snowplow-stream-collector",
  description := "Scala Stream Collector for Snowplow raw events",
  scalaVersion := "2.12.10",
  scalacOptions ++= Seq("-Ypartial-unification", "-Ywarn-macros:after"),
  javacOptions := Seq("-source", "11", "-target", "11"),
  resolvers ++= Dependencies.resolutionRepos
)

lazy val dynVerSettings = Seq(
  ThisBuild / dynverVTagPrefix := false, // Otherwise git tags required to have v-prefix
  ThisBuild / dynverSeparator := "-"     // to be compatible with docker
)

lazy val http4sBuildInfoSettings = Seq(
  buildInfoKeys := Seq[BuildInfoKey](name, dockerAlias, version),
  buildInfoOptions += BuildInfoOption.Traits("com.snowplowanalytics.snowplow.collector.core.AppInfo")
)

lazy val allSettings = buildSettings ++
  BuildSettings.sbtAssemblySettings ++
  BuildSettings.formatting ++
  Seq(excludeDependencies ++= commonExclusions) ++
  dynVerSettings ++
  BuildSettings.addExampleConfToTestCp

lazy val root = project
  .in(file("."))
  .settings(buildSettings ++ dynVerSettings)
  .aggregate(core, kinesis, pubsub, kafka, nsq, stdout, sqs, rabbitmq, http4s)

lazy val core = project
  .settings(moduleName := "snowplow-stream-collector-core")
  .settings(buildSettings ++ BuildSettings.sbtAssemblySettings)
  .settings(libraryDependencies ++= commonDependencies)
  .settings(excludeDependencies ++= commonExclusions)
  .settings(Defaults.itSettings)
  .configs(IntegrationTest)

lazy val http4s = project
  .settings(moduleName := "snowplow-stream-collector-http4s-core")
  .settings(buildSettings ++ BuildSettings.sbtAssemblySettings)
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.Libraries.http4sDsl,
      Dependencies.Libraries.http4sEmber,
      Dependencies.Libraries.http4sBlaze,
      Dependencies.Libraries.http4sNetty,
      Dependencies.Libraries.log4cats,
      Dependencies.Libraries.thrift,
      Dependencies.Libraries.badRows,
      Dependencies.Libraries.collectorPayload,
      Dependencies.Libraries.slf4j,
      Dependencies.Libraries.decline,
      Dependencies.Libraries.circeGeneric,
      Dependencies.Libraries.circeConfig,
      Dependencies.Libraries.specs2CE3
    )
  )

lazy val kinesisSettings =
  allSettings ++ buildInfoSettings ++ Defaults.itSettings ++ scalifiedSettings ++ Seq(
    moduleName := "snowplow-stream-collector-kinesis",
    Docker / packageName := "scala-stream-collector-kinesis",
    libraryDependencies ++= Seq(
      Dependencies.Libraries.kinesis,
      Dependencies.Libraries.sts,
      Dependencies.Libraries.sqs,
      // integration tests dependencies
      Dependencies.Libraries.specs2It,
      Dependencies.Libraries.specs2CEIt
    ),
    IntegrationTest / test := (IntegrationTest / test).dependsOn(Docker / publishLocal).value,
    IntegrationTest / testOnly := (IntegrationTest / testOnly).dependsOn(Docker / publishLocal).evaluated
  )

lazy val kinesis = project
  .settings(kinesisSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile;it->it")
  .configs(IntegrationTest)

lazy val kinesisDistroless = project
  .in(file("distroless/kinesis"))
  .settings(sourceDirectory := (kinesis / sourceDirectory).value)
  .settings(kinesisSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDistrolessDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile;it->it")
  .configs(IntegrationTest)

lazy val sqsSettings =
  allSettings ++ buildInfoSettings ++ Seq(
    moduleName := "snowplow-stream-collector-sqs",
    Docker / packageName := "scala-stream-collector-sqs",
    libraryDependencies ++= Seq(
      Dependencies.Libraries.sqs,
      Dependencies.Libraries.sts,
    )
  )

lazy val sqs = project
  .settings(sqsSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")

lazy val sqsDistroless = project
  .in(file("distroless/sqs"))
  .settings(sourceDirectory := (sqs / sourceDirectory).value)
  .settings(sqsSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDistrolessDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")

lazy val pubsubSettings =
  allSettings ++ buildInfoSettings ++ Defaults.itSettings ++ scalifiedSettings ++ Seq(
    moduleName := "snowplow-stream-collector-google-pubsub",
    Docker / packageName := "scala-stream-collector-pubsub",
    libraryDependencies ++= Seq(
      Dependencies.Libraries.pubsub,
      Dependencies.Libraries.protobuf,
      // integration tests dependencies
      Dependencies.Libraries.specs2It,
      Dependencies.Libraries.specs2CEIt,
    ),
    IntegrationTest / test := (IntegrationTest / test).dependsOn(Docker / publishLocal).value,
    IntegrationTest / testOnly := (IntegrationTest / testOnly).dependsOn(Docker / publishLocal).evaluated
  )

lazy val pubsub = project
  .settings(pubsubSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile;it->it")
  .configs(IntegrationTest)

lazy val pubsubDistroless = project
  .in(file("distroless/pubsub"))
  .settings(sourceDirectory := (pubsub / sourceDirectory).value)
  .settings(pubsubSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDistrolessDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile;it->it")
  .configs(IntegrationTest)

lazy val kafkaSettings =
  allSettings ++ buildInfoSettings ++ Seq(
    moduleName := "snowplow-stream-collector-kafka",
    Docker / packageName := "scala-stream-collector-kafka",
    libraryDependencies ++= Seq(Dependencies.Libraries.kafkaClients, Dependencies.Libraries.mskAuth)
  )

lazy val kafka = project
  .settings(kafkaSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")

lazy val kafkaDistroless = project
  .in(file("distroless/kafka"))
  .settings(sourceDirectory := (kafka / sourceDirectory).value)
  .settings(kafkaSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDistrolessDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")

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
  .settings(nsqSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")

lazy val nsqDistroless = project
  .in(file("distroless/nsq"))
  .settings(sourceDirectory := (nsq / sourceDirectory).value)
  .settings(nsqSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDistrolessDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")

lazy val stdoutSettings =
  allSettings ++ buildInfoSettings ++ http4sBuildInfoSettings ++ Seq(
    moduleName := "snowplow-stream-collector-stdout",
    Docker / packageName := "scala-stream-collector-stdout"
  )

lazy val stdout = project
  .settings(stdoutSettings)
  .settings(buildInfoPackage := s"com.snowplowanalytics.snowplow.collector.stdout")
  .enablePlugins(JavaAppPackaging, SnowplowDockerPlugin, BuildInfoPlugin)
  .dependsOn(http4s % "test->test;compile->compile")

lazy val stdoutDistroless = project
  .in(file("distroless/stdout"))
  .settings(sourceDirectory := (stdout / sourceDirectory).value)
  .settings(stdoutSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDistrolessDockerPlugin, BuildInfoPlugin)
  .dependsOn(http4s % "test->test;compile->compile")

lazy val rabbitmqSettings =
  allSettings ++ buildInfoSettings ++ Seq(
    moduleName := "snowplow-stream-collector-rabbitmq",
    Docker / packageName := "scala-stream-collector-rabbitmq-experimental",
    libraryDependencies ++= Seq(Dependencies.Libraries.rabbitMQ)
  )

lazy val rabbitmq = project
  .settings(rabbitmqSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")

lazy val rabbitmqDistroless = project
  .in(file("distroless/rabbitmq"))
  .settings(sourceDirectory := (rabbitmq / sourceDirectory).value)
  .settings(rabbitmqSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDistrolessDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")
