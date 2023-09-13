/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This program is licensed to you under the Snowplow Community License Version 1.0,
 * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
 * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
 */
import sbt._

object Dependencies {

  val resolutionRepos = Seq(
    "Snowplow Analytics Maven repo".at("http://maven.snplow.com/releases/").withAllowInsecureProtocol(true),
    // For uaParser utils
    "user-agent-parser repo".at("https://clojars.org/repo/")
  )

  object V {
    // Java
    val awsSdk       = "1.12.327"
    val pubsub       = "1.119.1"
    val kafka        = "2.2.1"
    val mskAuth      = "1.1.1"
    val nsqClient    = "1.3.0"
    val jodaTime     = "2.10.13"
    val slf4j        = "1.7.32"
    val log4j        = "2.17.2" // CVE-2021-44228
    val config       = "1.4.1"
    val rabbitMQ     = "5.15.0"
    val jackson      = "2.12.7" // force this version to mitigate security vulnerabilities
    val thrift       = "0.15.0" // force this version to mitigate security vulnerabilities
    val jnrUnixsock  = "0.38.17" // force this version to mitigate security vulnerabilities
    val protobuf     = "3.21.7" // force this version to mitigate security vulnerabilities
    // Scala
    val collectorPayload = "0.0.0"
    val tracker          = "2.0.0"
    val akkaHttp         = "10.2.7"
    val akka             = "2.6.16"
    val scopt            = "4.0.1"
    val pureconfig       = "0.17.2"
    val akkaHttpMetrics  = "1.7.1"
    val badRows          = "2.2.1"
    val log4cats         = "2.6.0"
    val http4s           = "0.23.23"
    val blaze            = "0.23.15"
    val http4sNetty      = "0.5.9"
    val decline          = "2.4.1"
    val circe            = "0.14.1"
    val circeConfig      = "0.10.0"
    val fs2PubSub        = "0.22.0"
    val catsRetry        = "3.1.0"
    val nettyAll         = "4.1.95.Final" // to fix nsq dependency

    // Scala (test only)
    val specs2         = "4.11.0"
    val specs2CE       = "1.5.0"
    val testcontainers = "0.40.10"

    object Legacy {
      val specs2CE  = "0.4.1"
      val catsRetry = "2.1.0"
      val http4s    = "0.21.33"
      val tracker   = "1.0.1"
    }
  }

  object Libraries {
    // Java
    val jackson        = "com.fasterxml.jackson.core"       % "jackson-databind"        % V.jackson // nsq only
    val nettyAll       = "io.netty"                         % "netty-all"               % V.nettyAll //nsq only
    val thrift         = "org.apache.thrift"                % "libthrift"               % V.thrift
    val kinesis        = "com.amazonaws"                    % "aws-java-sdk-kinesis"    % V.awsSdk
    val sqs            = "com.amazonaws"                    % "aws-java-sdk-sqs"        % V.awsSdk
    val sts            = "com.amazonaws"                    % "aws-java-sdk-sts"        % V.awsSdk % Runtime // Enables web token authentication https://github.com/snowplow/stream-collector/issues/169
    val pubsub         = "com.google.cloud"                 % "google-cloud-pubsub"     % V.pubsub
    val kafkaClients   = "org.apache.kafka"                 % "kafka-clients"           % V.kafka
    val mskAuth        = "software.amazon.msk"              % "aws-msk-iam-auth"        % V.mskAuth % Runtime // Enables AWS MSK IAM authentication https://github.com/snowplow/stream-collector/pull/214
    val nsqClient      = "com.snowplowanalytics"            % "nsq-java-client"         % V.nsqClient
    val jodaTime       = "joda-time"                        % "joda-time"               % V.jodaTime
    val slf4j          = "org.slf4j"                        % "slf4j-simple"            % V.slf4j
    val log4jOverSlf4j = "org.slf4j"                        % "log4j-over-slf4j"        % V.slf4j
    val log4j          = "org.apache.logging.log4j"         % "log4j-core"              % V.log4j
    val config         = "com.typesafe"                     % "config"                  % V.config
    val jnrUnixsocket  = "com.github.jnr"                   % "jnr-unixsocket"          % V.jnrUnixsock
    val rabbitMQ       = "com.rabbitmq"                     % "amqp-client"             % V.rabbitMQ
    val protobuf       = "com.google.protobuf"              % "protobuf-java"           % V.protobuf

    // Scala
    val collectorPayload  = "com.snowplowanalytics" % "collector-payload-1"                    % V.collectorPayload
    val badRows           = "com.snowplowanalytics" %% "snowplow-badrows"                      % V.badRows
    val trackerCore       = "com.snowplowanalytics" %% "snowplow-scala-tracker-core"           % V.tracker
    val emitterHttps      = "com.snowplowanalytics" %% "snowplow-scala-tracker-emitter-http4s" % V.tracker
    val scopt             = "com.github.scopt"      %% "scopt"                                 % V.scopt
    val akkaHttp          = "com.typesafe.akka"     %% "akka-http"                             % V.akkaHttp
    val akkaStream        = "com.typesafe.akka"     %% "akka-stream"                           % V.akka
    val akkaSlf4j         = "com.typesafe.akka"     %% "akka-slf4j"                            % V.akka
    val pureconfig        = "com.github.pureconfig" %% "pureconfig"                            % V.pureconfig
    val akkaHttpMetrics   = "fr.davit"              %% "akka-http-metrics-datadog"             % V.akkaHttpMetrics
    val log4cats          = "org.typelevel"         %% "log4cats-slf4j"                        % V.log4cats

    // http4s
    val http4sDsl    = "org.http4s"       %% "http4s-dsl"             % V.http4s
    val http4sEmber  = "org.http4s"       %% "http4s-ember-server"    % V.http4s
    val http4sBlaze  = "org.http4s"       %% "http4s-blaze-server"    % V.blaze
    val http4sNetty  = "org.http4s"       %% "http4s-netty-server"    % V.http4sNetty
    val http4sClient = "org.http4s"       %% "http4s-blaze-client"    % V.blaze
    val decline      = "com.monovore"     %% "decline-effect"         % V.decline
    val circeGeneric = "io.circe"         %% "circe-generic"          % V.circe
    val circeConfig  = "io.circe"         %% "circe-config"           % V.circeConfig
    val catsRetry    = "com.github.cb372" %% "cats-retry"             % V.catsRetry
    val fs2PubSub    = "com.permutive"    %% "fs2-google-pubsub-grpc" % V.fs2PubSub

    // Scala (test only)

    // Test common
    val specs2   = "org.specs2"    %% "specs2-core"                % V.specs2    % Test
    val specs2CE = "org.typelevel" %% "cats-effect-testing-specs2" % V.specs2CE  % Test

    // Test Akka
    val akkaTestkit = "com.typesafe.akka" %% "akka-testkit" % V.akka % Test
    val akkaHttpTestkit = "com.typesafe.akka" %% "akka-http-testkit" % V.akkaHttp % Test
    val akkaStreamTestkit = "com.typesafe.akka" %% "akka-stream-testkit" % V.akka % Test

    // Integration tests
    object IT {
      val testcontainers = "com.dimafeng" %% "testcontainers-scala-core" % V.testcontainers % IntegrationTest
      val specs2 = "org.specs2" %% "specs2-core" % V.specs2 % IntegrationTest
      val specs2CE = "org.typelevel" %% "cats-effect-testing-specs2" % V.specs2CE % IntegrationTest
      val catsRetry = "com.github.cb372" %% "cats-retry" % V.catsRetry % IntegrationTest
      val http4sClient = "org.http4s" %% "http4s-blaze-client" % V.blaze % IntegrationTest
    }

    object Legacy {
      val testcontainers = "com.dimafeng" %% "testcontainers-scala-core" % V.testcontainers % IntegrationTest
      val specs2 = "org.specs2" %% "specs2-core" % V.specs2 % IntegrationTest
      val specs2CE = "com.codecommit" %% "cats-effect-testing-specs2" % V.Legacy.specs2CE % IntegrationTest
      val catsRetry = "com.github.cb372" %% "cats-retry" % V.Legacy.catsRetry % IntegrationTest
      val http4sClient = "org.http4s" %% "http4s-blaze-client" % V.Legacy.http4s % IntegrationTest
      val trackerCore = "com.snowplowanalytics" %% "snowplow-scala-tracker-core" % V.Legacy.tracker
      val trackerEmitterId = "com.snowplowanalytics" %% "snowplow-scala-tracker-emitter-id" % V.Legacy.tracker
    }
  }
}
