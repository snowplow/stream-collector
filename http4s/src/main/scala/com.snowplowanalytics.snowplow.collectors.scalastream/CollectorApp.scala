package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect.{ExitCode, IO}
import com.comcast.ip4s.IpLiteralSyntax
import org.http4s.ember.server.EmberServerBuilder

object CollectorApp {

  def run(): IO[ExitCode] =
    buildHttpServer().use(_ => IO.never).as(ExitCode.Success)

  private def buildHttpServer() =
    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(port"8080")
      .withHttpApp(new CollectorRoutes[IO].value)
      .build
}
