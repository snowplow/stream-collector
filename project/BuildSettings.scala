/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Snowplow Community License Version 1.0,
 * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
 * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
 */

import com.typesafe.sbt.packager.Keys.packageName
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport._
import org.scalafmt.sbt.ScalafmtPlugin.autoImport._
import sbt.Keys._
import sbt._
import sbtassembly.AssemblyPlugin.autoImport._
import sbtassembly.MergeStrategy
import sbtbuildinfo.BuildInfoPlugin.autoImport._
import sbtdynver.DynVerPlugin.autoImport._


object BuildSettings {

  lazy val commonSettings = Seq(
    organization   := "com.snowplowanalytics",
    name           := "snowplow-stream-collector",
    description    := "Scala Stream Collector for Snowplow raw events",
    scalaVersion   := "2.13.12",
    scalacOptions ++= Seq("-Ywarn-macros:after"),
    javacOptions   := Seq("-source", "11", "-target", "11"),
    resolvers     ++= Seq(
      "Snowplow Analytics Maven repo".at("http://maven.snplow.com/releases/").withAllowInsecureProtocol(true),
      // For uaParser utils
      "user-agent-parser repo".at("https://clojars.org/repo/")
    )
  )

  lazy val coreHttp4sSettings = commonSettings ++ sbtAssemblySettings ++ Defaults.itSettings
  
  lazy val kinesisSettings =
    commonSinkSettings ++ integrationTestSettings ++ Seq(
      moduleName := "snowplow-stream-collector-kinesis",
      Docker / packageName := "scala-stream-collector-kinesis",
      libraryDependencies ++= Seq(
        Dependencies.Libraries.catsRetry,
        Dependencies.Libraries.kinesis,
        Dependencies.Libraries.sts,
        Dependencies.Libraries.sqs,
        
        // integration tests dependencies
        Dependencies.Libraries.IntegrationTests.specs2,
        Dependencies.Libraries.IntegrationTests.specs2CE,
      )
    )

  lazy val sqsSettings =
    commonSinkSettings ++ Seq(
      moduleName := "snowplow-stream-collector-sqs",
      Docker / packageName := "scala-stream-collector-sqs",
      libraryDependencies ++= Seq(
        Dependencies.Libraries.catsRetry,
        Dependencies.Libraries.sqs,
        Dependencies.Libraries.sts,
      )
    )

  lazy val pubsubSettings =
    commonSinkSettings ++ integrationTestSettings ++ Seq(
      moduleName := "snowplow-stream-collector-google-pubsub",
      Docker / packageName := "scala-stream-collector-pubsub",
      libraryDependencies ++= Seq(
        Dependencies.Libraries.catsRetry,
        Dependencies.Libraries.fs2PubSub,
        Dependencies.Libraries.pubsub,
        
        // integration tests dependencies
        Dependencies.Libraries.IntegrationTests.specs2,
        Dependencies.Libraries.IntegrationTests.specs2CE,
      )
    )


  lazy val kafkaSettings =
    commonSinkSettings ++ integrationTestSettings ++ Seq(
      moduleName := "snowplow-stream-collector-kafka",
      Docker / packageName := "scala-stream-collector-kafka",
      libraryDependencies ++= Seq(
        Dependencies.Libraries.kafkaClients,
        Dependencies.Libraries.mskAuth,
        
        // integration tests dependencies
        Dependencies.Libraries.IntegrationTests.specs2,
        Dependencies.Libraries.IntegrationTests.specs2CE
      )
    )

  lazy val nsqSettings =
    commonSinkSettings ++ Seq(
      moduleName := "snowplow-stream-collector-nsq",
      Docker / packageName := "scala-stream-collector-nsq",
      libraryDependencies ++= Seq(
        Dependencies.Libraries.nsqClient,
        Dependencies.Libraries.jackson,
        Dependencies.Libraries.nettyAll,
        Dependencies.Libraries.log4j
      )
    )
  
  lazy val stdoutSettings =
    commonSinkSettings ++ Seq(
      moduleName := "snowplow-stream-collector-stdout",
      buildInfoPackage := s"com.snowplowanalytics.snowplow.collector.stdout",
      Docker / packageName := "scala-stream-collector-stdout"
    )

  lazy val commonSinkSettings =
    commonSettings ++
      buildInfoSettings ++
      sbtAssemblySettings ++
      formatting ++
      dynVerSettings ++
      addExampleConfToTestCp

  lazy val buildInfoSettings = Seq(
    buildInfoKeys := Seq[BuildInfoKey](name, moduleName, dockerAlias, version),
    buildInfoOptions += BuildInfoOption.Traits("com.snowplowanalytics.snowplow.collector.core.AppInfo"),
    buildInfoPackage := s"com.snowplowanalytics.snowplow.collectors.scalastream"
  )

  lazy val dynVerSettings = Seq(
    ThisBuild / dynverVTagPrefix := false, // Otherwise git tags required to have v-prefix
    ThisBuild / dynverSeparator := "-" // to be compatible with docker
  )
  
  lazy val sbtAssemblySettings = Seq(
    assembly / assemblyJarName := { s"${moduleName.value}-${version.value}.jar" },
    assembly / assemblyMergeStrategy := {
      // merge strategy for fixing netty conflict
      case PathList("io", "netty", xs @ _*)                     => MergeStrategy.first
      case fileName if fileName.toLowerCase == "reference.conf" => reverseConcat
      case x if x.endsWith("io.netty.versions.properties")      => MergeStrategy.discard
      case x if x.endsWith("module-info.class")                 => MergeStrategy.first
      case x if x.endsWith("paginators-1.json")                 => MergeStrategy.first
      case x if x.endsWith("service-2.json")                    => MergeStrategy.first
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )

  lazy val reverseConcat: MergeStrategy = new MergeStrategy {
    val name = "reverseConcat"

    def apply(tempDir: File, path: String, files: Seq[File]): Either[String, Seq[(File, String)]] =
      MergeStrategy.concat(tempDir, path, files.reverse)
  }
  
  lazy val formatting = Seq(
    scalafmtConfig := file(".scalafmt.conf"),
    scalafmtOnCompile := true
  )

  lazy val addExampleConfToTestCp = Seq(
    Test / unmanagedClasspath += {
      baseDirectory.value.getParentFile / "examples"
    }
  )

  lazy val integrationTestSettings = Defaults.itSettings ++ scalifiedSettings ++ Seq(
    IntegrationTest / test := (IntegrationTest / test).dependsOn(Docker / publishLocal).value,
    IntegrationTest / testOnly := (IntegrationTest / testOnly).dependsOn(Docker / publishLocal).evaluated
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

}
