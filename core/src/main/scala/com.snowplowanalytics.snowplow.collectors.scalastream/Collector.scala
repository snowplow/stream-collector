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
package com.snowplowanalytics.snowplow.collectors.scalastream

import java.io.File
import javax.net.ssl.SSLContext
import org.slf4j.LoggerFactory
import akka.actor.ActorSystem
import akka.http.scaladsl.{ConnectionContext, Http, ServerBuilder}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import com.typesafe.config.{Config, ConfigFactory}
import pureconfig._
import pureconfig.generic.auto._
import pureconfig.generic.{FieldCoproductHint, ProductHint}
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.Sink
import com.snowplowanalytics.snowplow.collectors.scalastream.metrics._
import com.snowplowanalytics.snowplow.collectors.scalastream.model._
import com.snowplowanalytics.snowplow.collectors.scalastream.telemetry.TelemetryAkkaService

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

// Main entry point of the Scala collector.
trait Collector {

  def appName: String

  def scalaVersion: String

  def appVersion: String

  lazy val log = LoggerFactory.getLogger(getClass())

  implicit def hint[T] = ProductHint[T](ConfigFieldMapping(CamelCase, CamelCase))
  implicit val _       = new FieldCoproductHint[SinkConfig]("enabled")

  // Used as an option prefix when reading system properties.
  val Namespace = "collector"

  /** Optionally give precedence to configs wrapped in a "snowplow" block. To help avoid polluting system namespace */
  private def namespaced(config: Config): Config =
    if (config.hasPath(Namespace))
      config.getConfig(Namespace).withFallback(config.withoutPath(Namespace))
    else
      config

  def parseConfig(args: Array[String]): (CollectorConfig, Config) = {
    case class FileConfig(config: File = new File("."))

    val parser = new scopt.OptionParser[FileConfig](appName) {
      head(appName, appVersion)
      help("help")
      version("version")
      opt[File]("config")
        .required()
        .valueName("<filename>")
        .action((f: File, c: FileConfig) => c.copy(f))
        .validate(f =>
          if (f.exists) success
          else failure(s"Configuration file $f does not exist")
        )
    }

    val resolved = parser.parse(args, FileConfig()) match {
      case Some(c) => ConfigFactory.parseFile(c.config).resolve()
      case None    => ConfigFactory.empty()
    }

    val conf = namespaced(ConfigFactory.load(namespaced(resolved.withFallback(namespaced(ConfigFactory.load())))))

    (ConfigSource.fromConfig(conf).loadOrThrow[CollectorConfig], conf)
  }

  def run(
    collectorConf: CollectorConfig,
    akkaConf: Config,
    sinks: CollectorSinks,
    telemetry: TelemetryAkkaService
  ): Unit = {

    implicit val system           = ActorSystem.create("scala-stream-collector", akkaConf)
    implicit val executionContext = system.dispatcher

    telemetry.start()

    val health = new HealthService.Settable

    val collectorRoute = new CollectorRoute {
      override def collectorService = new CollectorService(collectorConf, sinks, appName, appVersion)
      override def healthService    = health
    }

    val prometheusMetricsService =
      new PrometheusMetricsService(collectorConf.prometheusMetrics, scalaVersion, appVersion)

    val metricsRoute = new MetricsRoute {
      override def metricsService: MetricsService = prometheusMetricsService
    }

    val metricsDirectives = new MetricsDirectives {
      override def metricsService: MetricsService = prometheusMetricsService
    }

    val routes =
      if (collectorConf.prometheusMetrics.enabled)
        metricsRoute.metricsRoute ~ metricsDirectives.logRequest(collectorRoute.collectorRoute)
      else collectorRoute.collectorRoute

    lazy val redirectRoutes =
      scheme("http") {
        extract(_.request.uri) { uri =>
          redirect(
            uri.copy(scheme = "https").withPort(collectorConf.ssl.port),
            StatusCodes.MovedPermanently
          )
        }
      }

    def bindRoutes(
      builder: ServerBuilder,
      rs: Route
    ): Future[Unit] =
      builder
        .bind(rs)
        .map(_.addToCoordinatedShutdown(collectorConf.terminationDeadline))
        .map { binding =>
          log.info(s"REST interface bound to ${binding.localAddress}")
        }
        .recover {
          case ex =>
            log.error(
              "REST interface could not be bound to " +
                s"${collectorConf.interface}:${collectorConf.port}",
              ex.getMessage
            )
        }

    lazy val secureEndpoint: Future[Unit] =
      bindRoutes(
        Http()
          .newServerAt(collectorConf.interface, collectorConf.ssl.port)
          .enableHttps(ConnectionContext.httpsServer(SSLContext.getDefault)),
        routes
      )

    def unsecureEndpoint(routes: Route): Future[Unit] =
      bindRoutes(
        Http().newServerAt(collectorConf.interface, collectorConf.port),
        routes
      )

    collectorConf.ssl match {
      case SSLConfig(true, true, _) =>
        unsecureEndpoint(redirectRoutes)
        secureEndpoint
        ()
      case SSLConfig(true, false, _) =>
        unsecureEndpoint(routes)
        secureEndpoint
        ()
      case _ =>
        unsecureEndpoint(routes)
        ()
    }

    Runtime
      .getRuntime
      .addShutdownHook(new Thread(() => {
        log.warn("Received shutdown signal")
        if (collectorConf.preTerminationUnhealthy) {
          log.warn("setting health endpoint to unhealthy")
          health.toUnhealthy()
        }
        log.warn(s"Sleeping for ${collectorConf.preTerminationPeriod}")
        Thread.sleep(collectorConf.preTerminationPeriod.toMillis)
        log.warn("Initiating http server termination")
        try {
          // The actor system is already configured to shutdown within `terminationDeadline` so we await for double that.
          Await.result(system.terminate(), collectorConf.terminationDeadline * 2)
          log.warn("Server terminated")
        } catch {
          case NonFatal(t) =>
            log.error("Caught exception awaiting server termination", t)
        }
        val shutdowns = List(shutdownSink(sinks.good, "good"), shutdownSink(sinks.bad, "bad"))
        Await.result(Future.sequence(shutdowns), Duration.Inf)
        ()
      }))
  }

  private def shutdownSink(sink: Sink, label: String)(implicit ec: ExecutionContext): Future[Unit] =
    Future {
      log.warn(s"Initiating $label sink shutdown")
      sink.shutdown()
      log.warn(s"Completed $label sink shutdown")
    }.recover {
      case NonFatal(t) =>
        log.error(s"Caught exception shutting down $label sink", t)
    }
}
