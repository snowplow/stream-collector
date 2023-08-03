package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.{Method, Request, RequestCookie, Response, Status}
import org.http4s.Status._
import fs2.{Stream, text}
import org.specs2.mutable.Specification

class CollectorRoutesSpec extends Specification {

  val collectorService = new Service[IO] {
    override def cookie(
      queryString: Option[String],
      body: IO[Option[String]],
      path: String,
      cookie: Option[RequestCookie],
      userAgent: Option[String],
      refererUri: Option[String],
      hostname: IO[Option[String]],
      ip: Option[String],
      request: Request[IO],
      pixelExpected: Boolean,
      doNotTrack: Boolean,
      contentType: Option[String],
      spAnonymous: Option[String]
    ): IO[Response[IO]] =
      IO.pure(Response(status = Ok, body = Stream.emit("cookie").through(text.utf8.encode)))

    override def determinePath(vendor: String, version: String): String = "/p1/p2"
  }
  val routes = new CollectorRoutes[IO](collectorService).value

  "The collector route" should {
    "respond to the health route with an ok response" in {
      val request  = Request[IO](method = Method.GET, uri = uri"/health")
      val response = routes.run(request).unsafeRunSync()

      response.status must beEqualTo(Status.Ok)
      response.as[String].unsafeRunSync() must beEqualTo("OK")
    }

    "respond to the post cookie route with the cookie response" in {
      val request  = Request[IO](method = Method.POST, uri = uri"/p1/p2")
      val response = routes.run(request).unsafeRunSync()

      response.status must beEqualTo(Status.Ok)
      response.bodyText.compile.string.unsafeRunSync() must beEqualTo("cookie")
    }
  }

}
