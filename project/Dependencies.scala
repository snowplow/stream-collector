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

  object V {
    val awsSdk           = "1.12.327"
    val badRows          = "2.2.1"
    val blaze            = "0.23.15"
    val catsRetry        = "3.1.0"
    val ceTestkit        = "3.4.5"
    val circe            = "0.14.1"
    val circeConfig      = "0.10.0"
    val collectorPayload = "0.0.0"
    val decline          = "2.4.1"
    val fs2PubSub        = "0.22.0"
    val http4s           = "0.23.23"
    val jackson          = "2.12.7" // force this version to mitigate security vulnerabilities
    val kafka            = "2.2.1"
    val log4cats         = "2.6.0"
    val log4j            = "2.17.2" // CVE-2021-44228
    val mskAuth          = "1.1.1"
    val nettyAll         = "4.1.95.Final" // to fix nsq dependency
    val nsqClient        = "1.3.0"
    val pubsub           = "1.125.11" // force this version to mitigate security vulnerabilities
    val rabbitMQ         = "5.15.0"
    val slf4j            = "1.7.32"
    val specs2           = "4.11.0"
    val specs2CE         = "1.5.0"
    val testcontainers   = "0.40.10"
    val thrift           = "0.15.0" // force this version to mitigate security vulnerabilities
    val tracker          = "2.0.0"
  }

  object Libraries {

    //common core
    val badRows           = "com.snowplowanalytics" %% "snowplow-badrows"                      % V.badRows
    val catsRetry         = "com.github.cb372"      %% "cats-retry"                            % V.catsRetry
    val circeConfig       = "io.circe"              %% "circe-config"                          % V.circeConfig
    val circeGeneric      = "io.circe"              %% "circe-generic"                         % V.circe
    val collectorPayload  = "com.snowplowanalytics" % "collector-payload-1"                    % V.collectorPayload
    val decline           = "com.monovore"          %% "decline-effect"                        % V.decline
    val emitterHttps      = "com.snowplowanalytics" %% "snowplow-scala-tracker-emitter-http4s" % V.tracker
    val http4sBlaze       = "org.http4s"            %% "http4s-blaze-server"                   % V.blaze
    val http4sClient      = "org.http4s"            %% "http4s-blaze-client"                   % V.blaze
    val http4sDsl         = "org.http4s"            %% "http4s-dsl"                            % V.http4s
    val log4cats          = "org.typelevel"         %% "log4cats-slf4j"                        % V.log4cats
    val slf4j             = "org.slf4j"             % "slf4j-simple"                           % V.slf4j
    val thrift            = "org.apache.thrift"     % "libthrift"                              % V.thrift
    val trackerCore       = "com.snowplowanalytics" %% "snowplow-scala-tracker-core"           % V.tracker
    
    //sinks
    val fs2PubSub      = "com.permutive"              %% "fs2-google-pubsub-grpc" % V.fs2PubSub
    val jackson        = "com.fasterxml.jackson.core" % "jackson-databind"        % V.jackson
    val kafkaClients   = "org.apache.kafka"           % "kafka-clients"           % V.kafka
    val kinesis        = "com.amazonaws"              % "aws-java-sdk-kinesis"    % V.awsSdk
    val log4j          = "org.apache.logging.log4j"   % "log4j-core"              % V.log4j
    val mskAuth        = "software.amazon.msk"        % "aws-msk-iam-auth"        % V.mskAuth % Runtime // Enables AWS MSK IAM authentication https://github.com/snowplow/stream-collector/pull/214
    val nettyAll       = "io.netty"                   % "netty-all"               % V.nettyAll
    val nsqClient      = "com.snowplowanalytics"      % "nsq-java-client"         % V.nsqClient
    val pubsub         = "com.google.cloud"           % "google-cloud-pubsub"     % V.pubsub
    val sqs            = "com.amazonaws"              % "aws-java-sdk-sqs"        % V.awsSdk
    val sts            = "com.amazonaws"              % "aws-java-sdk-sts"        % V.awsSdk % Runtime // Enables web token authentication https://github.com/snowplow/stream-collector/issues/169

    //common unit tests
    val specs2    = "org.specs2"     %% "specs2-core"                % V.specs2    % Test
    val specs2CE  = "org.typelevel"  %% "cats-effect-testing-specs2" % V.specs2CE  % Test
    val ceTestkit = "org.typelevel"  %% "cats-effect-testkit"        % V.ceTestkit % Test

    object IntegrationTests {
      val testcontainers = "com.dimafeng"     %% "testcontainers-scala-core"  % V.testcontainers % IntegrationTest
      val specs2         = "org.specs2"       %% "specs2-core"                % V.specs2         % IntegrationTest
      val specs2CE       = "org.typelevel"    %% "cats-effect-testing-specs2" % V.specs2CE       % IntegrationTest
      val catsRetry      = "com.github.cb372" %% "cats-retry"                 % V.catsRetry      % IntegrationTest
      val http4sClient   = "org.http4s"       %% "http4s-blaze-client"        % V.blaze          % IntegrationTest
    }
  }
}
