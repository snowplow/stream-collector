package com.snowplowanalytics.snowplow.collector.core

import org.specs2.mutable.Specification
import cats.effect.IO

import org.http4s.client.Client
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import scala.concurrent.duration._
import cats.effect.testing.specs2._

class HttpServerSpec extends Specification with CatsEffect {
  val routes = HttpRoutes.of[IO] {
    case _ -> Root / "fast" =>
      Ok("Fast")
    case _ -> Root / "never" =>
      IO.never[Response[IO]]
  }
  val healthRoutes = HttpRoutes.of[IO] {
    case _ -> Root / "health" =>
      Ok("ok")
  }

  "HttpServer" should {
    "manage request timeout" should {
      "timeout threshold is configured" in {
        val config =
          TestUtils
            .testConfig
            .copy(networking = TestUtils.testConfig.networking.copy(responseHeaderTimeout = 100.millis))
        val httpApp = HttpServer.httpApp(
          routes,
          healthRoutes,
          config.hsts,
          config.networking,
          config.debug.http
        )
        val client: Client[IO]   = Client.fromHttpApp(httpApp)
        val request: Request[IO] = Request(method = Method.GET, uri = uri"/never")
        val res: IO[String]      = client.expect[String](request)

        res
          .attempt
          .map(_ must beLeft[Throwable].which {
            case org.http4s.client.UnexpectedStatus(Status.RequestTimeout, _, _) => true
            case _                                                               => false
          })
      }
    }
  }
}
