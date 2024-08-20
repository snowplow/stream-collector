package com.snowplowanalytics.snowplow.collector.core

import org.specs2.mutable.Specification
import cats.effect.IO

import org.http4s.client.Client
import org.http4s._
import org.http4s.dsl.io._
import cats.implicits._
import org.http4s.implicits._
import scala.concurrent.duration._
import cats.effect.testing.specs2._

class HttpServerSpec extends Specification with CatsEffect {
  val routes = HttpRoutes.of[IO] {
    case r if r.pathInfo == path"/large" =>
      r.decode[String](Response[IO](Ok).withEntity(_).pure[IO])
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

        val request: Request[IO] = Request(method = Method.GET, uri = uri"/never")

        check(config, request)(
          _ must beLeft[Throwable].which {
            case org.http4s.client.UnexpectedStatus(Status.RequestTimeout, _, _) => true
            case _                                                               => false
          }
        )
      }
    }
    "manage request size" should {
      "drop requests larger than `networking.dropPayloadSize`" in {
        val config =
          TestUtils
            .testConfig
            .copy(networking = TestUtils.testConfig.networking.copy(maxPayloadSize = 5L, dropPayloadSize = 10L))
        val request: Request[IO] = Request(
          Method.POST,
          uri"/large"
        ).withEntity("s" * 1000)

        check(config, request)(
          _ must beLeft[Throwable].which {
            case org.http4s.client.UnexpectedStatus(Status.PayloadTooLarge, _, _) => true
            case _                                                                => false
          }
        )
      }
      "allow request that's smaller than `networking.dropPayloadSize`" in {
        val config =
          TestUtils.testConfig.copy(networking = TestUtils.testConfig.networking.copy(dropPayloadSize = 1002L))
        val body = "s" * 1000
        val request: Request[IO] = Request(
          Method.POST,
          uri"/large"
        ).withEntity(body)

        check(config, request)(_ must beRight(body))
      }
    }
  }

  private[this] def check(config: Config[Any], request: Request[IO])(assert: Either[Throwable, _] => Boolean) = {
    val httpApp = HttpServer.httpApp(
      routes,
      healthRoutes,
      config.hsts,
      config.networking
    )

    Client.fromHttpApp(httpApp).expect[String](request).attempt.map(assert)
  }

}
