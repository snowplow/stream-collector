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
import sbt._

object Dependencies {

  object V {
    // Snowplow libraries
    val commonStreams = "0.29.0"
    val badRows       = "2.3.0"
    val tracker       = "2.0.1"

    // Scala and FP core
    val circe       = "0.14.16"
    val circeConfig = "0.10.2"
    val decline     = "2.6.2"
    val catsRetry   = "3.1.3"

    // HTTP
    val http4s       = "0.23.37"
    val blaze        = "0.23.18" // Released separately from http4s since 0.23.13
    val commonsCodec = "1.20.0"

    // Thrift, for the collector payload format
    val thrift          = "0.24.0"
    val javaxAnnotation = "1.3.2"

    // Stream sinks, and the cloud SDKs and auth they need
    val awsSdk       = "2.54.13"
    val kafkaClients = "4.3.2"
    val mskAuth      = "2.3.8"

    // Logging and metrics
    val log4cats  = "2.8.0"
    val slf4j     = "2.0.19"
    val dataDog4s = "0.32.0"

    // Versions held above what something upstream asks for, to keep a vulnerable copy out of the
    // images. See the *SecurityOverrides Seqs below for where each one applies.
    val jnrPosix      = "3.2.2"
    val azureIdentity = "1.18.7"
    val jackson       = "2.22.4"
    val guava         = "33.7.2-jre"

    // Test only
    val specs2         = "4.23.0"
    val specs2CE       = "1.8.0"
    val ceTestkit      = "3.7.1"
    val testcontainers = "0.44.1"
  }

  object Libraries {

    // Snowplow libraries
    val commonStreams = "com.snowplowanalytics" %% "streams-core"                          % V.commonStreams
    val pubsub        = "com.snowplowanalytics" %% "pubsub"                                % V.commonStreams
    val nsq           = "com.snowplowanalytics" %% "nsq"                                   % V.commonStreams
    val kafka         = "com.snowplowanalytics" %% "kafka"                                 % V.commonStreams
    val badRows       = "com.snowplowanalytics" %% "snowplow-badrows"                      % V.badRows
    val trackerCore   = "com.snowplowanalytics" %% "snowplow-scala-tracker-core"           % V.tracker
    val emitterHttps  = "com.snowplowanalytics" %% "snowplow-scala-tracker-emitter-http4s" % V.tracker

    // Scala and FP core
    val circeGeneric = "io.circe"         %% "circe-generic"  % V.circe
    val circeConfig  = "io.circe"         %% "circe-config"   % V.circeConfig
    val decline      = "com.monovore"     %% "decline-effect" % V.decline
    val catsRetry    = "com.github.cb372" %% "cats-retry"     % V.catsRetry

    // HTTP
    val http4sDsl    = "org.http4s"    %% "http4s-dsl"          % V.http4s
    val http4sBlaze  = "org.http4s"    %% "http4s-blaze-server" % V.blaze
    val http4sClient = "org.http4s"    %% "http4s-blaze-client" % V.blaze
    val commonsCodec = "commons-codec" % "commons-codec"        % V.commonsCodec

    // Thrift, for the collector payload format
    val thrift = ("org.apache.thrift" % "libthrift" % V.thrift).excludeAll(
      ExclusionRule("org.apache.httpcomponents.client5"),
      ExclusionRule("org.apache.httpcomponents.core5"),
      ExclusionRule("jakarta.servlet"),
      ExclusionRule("jakarta.annotation")
    )
    // The thrift-generated classes are annotated with @javax.annotation.Generated
    val javaxAnnotation = "javax.annotation" % "javax.annotation-api" % V.javaxAnnotation

    // Stream sinks, and the cloud SDKs and auth they need
    val kinesis      = "software.amazon.awssdk" % "kinesis"          % V.awsSdk
    val sqs          = "software.amazon.awssdk" % "sqs"              % V.awsSdk
    val sts          = "software.amazon.awssdk" % "sts"              % V.awsSdk % Runtime // Enables web token authentication https://github.com/snowplow/stream-collector/issues/169
    val kafkaClients = "org.apache.kafka"       % "kafka-clients"    % V.kafkaClients
    val mskAuth      = "software.amazon.msk"    % "aws-msk-iam-auth" % V.mskAuth % Runtime // Enables AWS MSK IAM authentication https://github.com/snowplow/stream-collector/pull/214

    // Logging and metrics
    val log4cats      = "org.typelevel"   %% "log4cats-slf4j"   % V.log4cats
    val slf4j         = "org.slf4j"       % "slf4j-simple"      % V.slf4j
    val datadogHttp4s = "com.avast.cloud" %% "datadog4s-http4s" % V.dataDog4s
    val datadogStatsd = "com.avast.cloud" %% "datadog4s-statsd" % V.dataDog4s

    // Artifacts named only to force a version up; grouped into the *SecurityOverrides Seqs below
    val jnrPosix        = "com.github.jnr"             % "jnr-posix"        % V.jnrPosix
    val azureIdentity   = "com.azure"                  % "azure-identity"   % V.azureIdentity % Runtime
    val jacksonDatabind = "com.fasterxml.jackson.core" % "jackson-databind" % V.jackson
    val guava           = "com.google.guava"           % "guava"            % V.guava

    // Test only
    val specs2    = "org.specs2"    %% "specs2-core"                % V.specs2    % Test
    val specs2CE  = "org.typelevel" %% "cats-effect-testing-specs2" % V.specs2CE  % Test
    val ceTestkit = "org.typelevel" %% "cats-effect-testkit"        % V.ceTestkit % Test

    object IntegrationTests {
      val testcontainers = "com.dimafeng"     %% "testcontainers-scala-core"  % V.testcontainers % IntegrationTest
      val specs2         = "org.specs2"       %% "specs2-core"                % V.specs2         % IntegrationTest
      val specs2CE       = "org.typelevel"    %% "cats-effect-testing-specs2" % V.specs2CE       % IntegrationTest
      val catsRetry      = "com.github.cb372" %% "cats-retry"                 % V.catsRetry      % IntegrationTest
      val http4sClient   = "org.http4s"       %% "http4s-blaze-client"        % V.blaze          % IntegrationTest
    }

    // Security overrides, grouped by the module they apply to. An entry is needed only while it
    // still evicts something: once the upstream catches up, delete it. To check, drop the line and
    // compare the resolved runtime classpath before and after; reading the dependency tree for
    // eviction markers is unreliable, because sbt truncates long lines.

    // datadog4s-statsd, via java-dogstatsd-client and jnr-unixsocket, brings an older jnr-posix.
    // Naming jnr-posix pulls jnr-ffi, jffi, jnr-constants and asm up alongside it. This also covers
    // the sink modules, which depend on core.
    val coreSecurityOverrides = Seq(jnrPosix)

    // kafka from common-streams brings an older azure-identity, used for Event Hubs authentication.
    // Naming it pulls azure-core and azure-core-http-netty up alongside it. aws-msk-iam-auth and
    // kafka from common-streams bring an older jackson-databind; naming it pulls jackson-core up.
    val kafkaSecurityOverrides = Seq(azureIdentity, jacksonDatabind)

    // pubsub from common-streams, via google-cloud-pubsub, brings an older guava.
    val pubsubSecurityOverrides = Seq(guava)
  }
}
