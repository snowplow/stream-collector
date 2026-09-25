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
    val commonStreams   = "0.26.1"
    val awsSdk          = "2.54.13"
    val azureSdk        = "1.18.6" // Override version of transitive dependency
    val badRows         = "2.3.0"
    val blaze           = "0.23.18"
    val catsRetry       = "3.1.3"
    val ceTestkit       = "3.7.1"
    val circe           = "0.14.16"
    val circeConfig     = "0.10.2"
    val decline         = "2.6.2"
    val http4s          = "0.23.37"
    val kafkaClients    = "4.3.1"
    val log4cats        = "2.8.0"
    val mskAuth         = "2.3.8"
    val slf4j           = "2.0.19"
    val specs2          = "4.23.0"
    val specs2CE        = "1.8.0"
    val testcontainers  = "0.44.1"
    val thrift          = "0.24.0"
    val commonsCodec    = "1.20.0" // Override version of transitive dependency
    val javaxAnnotation = "1.3.2" // Override version of transitive dependency
    val httpClient5     = "5.6.4" // Override version of transitive dependency
    val tracker         = "2.0.1"
    val dataDog4s       = "0.32.0"
    val jnrPosix        = "3.2.2" // Override version of transitive dependency
    val httpClient      = "4.5.14" // Override version of transitive dependency
    val fs2             = "3.14.0" // Override version of transitive dependency
  }

  object Libraries {

    //common core
    val badRows         = "com.snowplowanalytics"             %% "snowplow-badrows"                      % V.badRows
    val catsRetry       = "com.github.cb372"                  %% "cats-retry"                            % V.catsRetry
    val circeConfig     = "io.circe"                          %% "circe-config"                          % V.circeConfig
    val circeGeneric    = "io.circe"                          %% "circe-generic"                         % V.circe
    val decline         = "com.monovore"                      %% "decline-effect"                        % V.decline
    val emitterHttps    = "com.snowplowanalytics"             %% "snowplow-scala-tracker-emitter-http4s" % V.tracker
    val http4sBlaze     = "org.http4s"                        %% "http4s-blaze-server"                   % V.blaze
    val http4sClient    = "org.http4s"                        %% "http4s-blaze-client"                   % V.blaze
    val http4sDsl       = "org.http4s"                        %% "http4s-dsl"                            % V.http4s
    val fs2io           = "co.fs2"                            %% "fs2-io"                                % V.fs2
    val log4cats        = "org.typelevel"                     %% "log4cats-slf4j"                        % V.log4cats
    val slf4j           = "org.slf4j"                         % "slf4j-simple"                           % V.slf4j
    val thrift          = "org.apache.thrift"                 % "libthrift"                              % V.thrift
    val trackerCore     = "com.snowplowanalytics"             %% "snowplow-scala-tracker-core"           % V.tracker
    val datadogHttp4s   = "com.avast.cloud"                   %% "datadog4s-http4s"                      % V.dataDog4s
    val datadogStatsd   = "com.avast.cloud"                   %% "datadog4s-statsd"                      % V.dataDog4s
    val jnrPosix        = "com.github.jnr"                    % "jnr-posix"                              % V.jnrPosix
    val commonsCodec    = "commons-codec"                     % "commons-codec"                          % V.commonsCodec
    val javaxAnnotation = "javax.annotation"                  % "javax.annotation-api"                   % V.javaxAnnotation
    val httpClient5     = "org.apache.httpcomponents.client5" % "httpclient5"                            % V.httpClient5
    val httpClient      = "org.apache.httpcomponents"         % "httpclient"                             % V.httpClient

    //sinks
    val commonStreams = "com.snowplowanalytics"  %% "streams-core"    % V.commonStreams
    val pubsub        = "com.snowplowanalytics"  %% "pubsub"          % V.commonStreams
    val nsq           = "com.snowplowanalytics"  %% "nsq"             % V.commonStreams
    val kafka         = "com.snowplowanalytics"  %% "kafka"           % V.commonStreams
    val kinesis       = "software.amazon.awssdk" % "kinesis"          % V.awsSdk
    val kafkaClients  = "org.apache.kafka"       % "kafka-clients"    % V.kafkaClients
    val mskAuth       = "software.amazon.msk"    % "aws-msk-iam-auth" % V.mskAuth % Runtime // Enables AWS MSK IAM authentication https://github.com/snowplow/stream-collector/pull/214
    val sqs           = "software.amazon.awssdk" % "sqs"              % V.awsSdk
    val sts           = "software.amazon.awssdk" % "sts"              % V.awsSdk % Runtime // Enables web token authentication https://github.com/snowplow/stream-collector/issues/169
    val azureIdentity = "com.azure"              % "azure-identity"   % V.azureSdk % Runtime // Enables Event Hub authentication

    //common unit tests
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
  }
}
