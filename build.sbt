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
  
lazy val root = project
  .in(file("."))
  .aggregate(kinesis, pubsub, kafka, nsq, stdout, sqs, core)

lazy val core = project
  .settings(moduleName := "snowplow-stream-collector-http4s-core")
  .settings(BuildSettings.coreHttp4sSettings)
  .settings(
    libraryDependencies ++= Seq(
      Dependencies.Libraries.http4sDsl,
      Dependencies.Libraries.http4sBlaze,
      Dependencies.Libraries.http4sClient,
      Dependencies.Libraries.log4cats,
      Dependencies.Libraries.thrift,
      Dependencies.Libraries.badRows,
      Dependencies.Libraries.collectorPayload,
      Dependencies.Libraries.slf4j,
      Dependencies.Libraries.decline,
      Dependencies.Libraries.circeGeneric,
      Dependencies.Libraries.circeConfig,
      Dependencies.Libraries.trackerCore,
      Dependencies.Libraries.emitterHttps,
      Dependencies.Libraries.datadogHttp4s,
      Dependencies.Libraries.datadogStatsd,
      Dependencies.Libraries.specs2,
      Dependencies.Libraries.specs2CE,
      Dependencies.Libraries.ceTestkit,
      Dependencies.Libraries.jnrPosix,

      //Integration tests
      Dependencies.Libraries.IntegrationTests.testcontainers,
      Dependencies.Libraries.IntegrationTests.http4sClient,
      Dependencies.Libraries.IntegrationTests.catsRetry

    )
  )
  .configs(IntegrationTest)

lazy val kinesis = project
  .settings(BuildSettings.kinesisSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile;it->it")
  .configs(IntegrationTest)

lazy val kinesisDistroless = project
  .in(file("distroless/kinesis"))
  .settings(sourceDirectory := (kinesis / sourceDirectory).value)
  .settings(BuildSettings.kinesisSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDistrolessDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile;it->it")
  .configs(IntegrationTest)

lazy val sqs = project
  .settings(BuildSettings.sqsSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")

lazy val sqsDistroless = project
  .in(file("distroless/sqs"))
  .settings(sourceDirectory := (sqs / sourceDirectory).value)
  .settings(BuildSettings.sqsSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDistrolessDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")

lazy val pubsub = project
  .settings(BuildSettings.pubsubSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile;it->it")
  .configs(IntegrationTest)

lazy val pubsubDistroless = project
  .in(file("distroless/pubsub"))
  .settings(sourceDirectory := (pubsub / sourceDirectory).value)
  .settings(BuildSettings.pubsubSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDistrolessDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile;it->it")
  .configs(IntegrationTest)

lazy val kafka = project
  .settings(BuildSettings.kafkaSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile;it->it")
  .configs(IntegrationTest)

lazy val kafkaDistroless = project
  .in(file("distroless/kafka"))
  .settings(sourceDirectory := (kafka / sourceDirectory).value)
  .settings(BuildSettings.kafkaSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDistrolessDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile;it->it")
  .configs(IntegrationTest)

lazy val nsq = project
  .settings(BuildSettings.nsqSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")

lazy val nsqDistroless = project
  .in(file("distroless/nsq"))
  .settings(sourceDirectory := (nsq / sourceDirectory).value)
  .settings(BuildSettings.nsqSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDistrolessDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")

lazy val stdout = project
  .settings(BuildSettings.stdoutSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")

lazy val stdoutDistroless = project
  .in(file("distroless/stdout"))
  .settings(sourceDirectory := (stdout / sourceDirectory).value)
  .settings(BuildSettings.stdoutSettings)
  .enablePlugins(JavaAppPackaging, SnowplowDistrolessDockerPlugin, BuildInfoPlugin)
  .dependsOn(core % "test->test;compile->compile")