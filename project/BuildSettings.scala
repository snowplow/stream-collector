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
import com.typesafe.sbt.packager.Keys.packageName
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport._
import org.scalafmt.sbt.ScalafmtPlugin.autoImport._
import sbt.Keys._
import sbt._
import sbtassembly.AssemblyPlugin.autoImport._
import org.typelevel.sbt.tpolecat.TpolecatPlugin.autoImport._
import org.typelevel.scalacoptions.ScalacOptions
import sbtassembly.MergeStrategy
import sbtbuildinfo.BuildInfoPlugin.autoImport._
import sbtdynver.DynVerPlugin.autoImport._

object BuildSettings {

  lazy val commonSettings = Seq(
    organization := "com.snowplowanalytics",
    name := "snowplow-stream-collector",
    description := "Scala Stream Collector for Snowplow raw events",
    scalaVersion := "2.13.18",
    crossScalaVersions := Seq("2.13.18", "2.12.20"),
    scalacOptions ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, n)) if n <= 12 =>
          Seq("-Ywarn-macros:after")
        case _ =>
          Nil
      }
    },
    javacOptions := Seq("-source", "21", "-target", "21"),
    // specs2 builds a specification out of expressions whose value is discarded, which
    // -Wnonunit-statement reports on every example. It is fatal in tpolecat's CI mode.
    Test / tpolecatExcludeOptions += ScalacOptions.warnNonUnitStatement,
    IntegrationTest / tpolecatExcludeOptions += ScalacOptions.warnNonUnitStatement,
    resolvers ++= Seq(
      // For uaParser utils
      "user-agent-parser repo".at("https://clojars.org/repo/")
    ),
    Compile / packageDoc / publishArtifact := false
  )

  lazy val coreHttp4sSettings = commonSettings ++ sbtAssemblySettings ++ Defaults.itSettings

  lazy val kinesisSettings =
    commonSinkSettings ++ integrationTestSettings ++ Seq(
      moduleName := "snowplow-stream-collector-kinesis",
      buildInfoKeys += BuildInfoKey("sinkName" -> "kinesis"),
      Docker / packageName := "scala-stream-collector-kinesis",
      libraryDependencies ++= Seq(
        Dependencies.Libraries.catsRetry,
        Dependencies.Libraries.kinesis,
        Dependencies.Libraries.sts,
        Dependencies.Libraries.sqs,
        // integration tests dependencies
        Dependencies.Libraries.IntegrationTests.specs2,
        Dependencies.Libraries.IntegrationTests.specs2CE
      )
    )

  lazy val sqsSettings =
    commonSinkSettings ++ Seq(
      moduleName := "snowplow-stream-collector-sqs",
      buildInfoKeys += BuildInfoKey("sinkName" -> "sqs"),
      Docker / packageName := "scala-stream-collector-sqs",
      libraryDependencies ++= Seq(
        Dependencies.Libraries.catsRetry,
        Dependencies.Libraries.sqs,
        Dependencies.Libraries.sts
      )
    )

  lazy val pubsubSettings =
    commonSinkSettings ++ integrationTestSettings ++ Seq(
      moduleName := "snowplow-stream-collector-google-pubsub",
      buildInfoKeys += BuildInfoKey("sinkName" -> "pubsub"),
      Docker / packageName := "scala-stream-collector-pubsub",
      libraryDependencies ++= Seq(
        Dependencies.Libraries.pubsub,
        // integration tests dependencies
        Dependencies.Libraries.IntegrationTests.specs2,
        Dependencies.Libraries.IntegrationTests.specs2CE
      )
    )

  lazy val kafkaSettings =
    commonSinkSettings ++ integrationTestSettings ++ Seq(
      moduleName := "snowplow-stream-collector-kafka",
      buildInfoKeys += BuildInfoKey("sinkName" -> "kafka"),
      Docker / packageName := "scala-stream-collector-kafka",
      libraryDependencies ++= Seq(
        Dependencies.Libraries.kafka,
        Dependencies.Libraries.kafkaClients,
        Dependencies.Libraries.mskAuth,
        Dependencies.Libraries.azureIdentity,
        // integration tests dependencies
        Dependencies.Libraries.IntegrationTests.specs2,
        Dependencies.Libraries.IntegrationTests.specs2CE
      )
    )

  lazy val nsqSettings =
    commonSinkSettings ++ Seq(
      moduleName := "snowplow-stream-collector-nsq",
      buildInfoKeys += BuildInfoKey("sinkName" -> "nsq"),
      Docker / packageName := "scala-stream-collector-nsq",
      libraryDependencies ++= Seq(
        Dependencies.Libraries.nsq,
        Dependencies.Libraries.httpClient
      )
    )

  lazy val stdoutSettings =
    commonSinkSettings ++ Seq(
      moduleName := "snowplow-stream-collector-stdout",
      buildInfoKeys += BuildInfoKey("sinkName" -> "printing"),
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
    ThisBuild / dynverSeparator := "-"     // to be compatible with docker
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
      case x if x.endsWith("FastDoubleParser-NOTICE")           => MergeStrategy.first
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    }
  )

  // MergeStrategy is a Function1 of the conflicting dependencies since sbt-assembly 2.x,
  // so concatenating them in reverse is a matter of handing them over reversed.
  lazy val reverseConcat: MergeStrategy =
    CustomMergeStrategy("reverseConcat") { dependencies =>
      MergeStrategy.concat(dependencies.reverse)
    }

  lazy val formatting = Seq(
    scalafmtConfig := file(".scalafmt.conf")
  )

  lazy val addExampleConfToTestCp = Seq(
    Test / unmanagedClasspath += {
      baseDirectory.value.getParentFile / "examples"
    }
  )

  lazy val integrationTestSettings = Defaults.itSettings ++ scalifiedSettings ++ Seq(
    IntegrationTest / test := (IntegrationTest     / test).dependsOn(Docker     / publishLocal).value,
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
