/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Snowplow Community License Version 1.0,
 * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
 * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
 */

// SBT
import sbt._
import Keys._

object BuildSettings {

  // sbt-assembly settings for building an executable
  import sbtassembly.AssemblyPlugin.autoImport._
  import sbtassembly.MergeStrategy

  val reverseConcat: MergeStrategy = new MergeStrategy {
    val name = "reverseConcat"
    def apply(tempDir: File, path: String, files: Seq[File]): Either[String, Seq[(File, String)]] =
      MergeStrategy.concat(tempDir, path, files.reverse)
  }

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

  // Scalafmt plugin
  import org.scalafmt.sbt.ScalafmtPlugin.autoImport._
  lazy val formatting = Seq(
    scalafmtConfig := file(".scalafmt.conf"),
    scalafmtOnCompile := true
  )

  lazy val addExampleConfToTestCp = Seq(
    Test / unmanagedClasspath += {
      baseDirectory.value.getParentFile / "examples"
    }
  )
}
