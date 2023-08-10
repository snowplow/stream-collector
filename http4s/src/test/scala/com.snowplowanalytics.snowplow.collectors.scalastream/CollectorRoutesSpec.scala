package com.snowplowanalytics.snowplow.collectors.scalastream

import scala.collection.mutable.ListBuffer
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.implicits._
import org.http4s._
import org.http4s.headers._
import org.http4s.Status._
import fs2.{Stream, text}
import org.specs2.mutable.Specification

class CollectorRoutesSpec extends Specification {

  case class CookieParams(
    body: IO[Option[String]],
    path: String,
    cookie: Option[RequestCookie],
    request: Request[IO],
    pixelExpected: Boolean,
    doNotTrack: Boolean,
    contentType: Option[String]
  )

  class TestService() extends Service[IO] {

    private val cookieCalls: ListBuffer[CookieParams] = ListBuffer()

    def getCookieCalls: List[CookieParams] = cookieCalls.toList

    override def cookie(
      body: IO[Option[String]],
      path: String,
      cookie: Option[RequestCookie],
      request: Request[IO],
      pixelExpected: Boolean,
      doNotTrack: Boolean,
      contentType: Option[String]
    ): IO[Response[IO]] =
      IO.delay {
        cookieCalls += CookieParams(
          body,
          path,
          cookie,
          request,
          pixelExpected,
          doNotTrack,
          contentType
        )
        Response(status = Ok, body = Stream.emit("cookie").through(text.utf8.encode))
      }

    override def determinePath(vendor: String, version: String): String = "/p1/p2"
  }

  def createTestServices = {
    val collectorService = new TestService()
    val routes           = new CollectorRoutes[IO](collectorService).value
    (collectorService, routes)
  }

  "The collector route" should {
    "respond to the health route with an ok response" in {
      val (_, routes) = createTestServices
      val request     = Request[IO](method = Method.GET, uri = uri"/health")
      val response    = routes.run(request).unsafeRunSync()

      response.status must beEqualTo(Status.Ok)
      response.as[String].unsafeRunSync() must beEqualTo("OK")
    }

    "respond to the post cookie route with the cookie response" in {
      val (collectorService, routes) = createTestServices

      val request = Request[IO](method = Method.POST, uri = uri"/p3/p4")
        .withEntity("testBody")
        .withHeaders(`Content-Type`(MediaType.application.json))
      val response = routes.run(request).unsafeRunSync()

      val List(cookieParams) = collectorService.getCookieCalls
      cookieParams.body.unsafeRunSync() shouldEqual Some("testBody")
      cookieParams.path shouldEqual "/p1/p2"
      cookieParams.cookie shouldEqual None
      cookieParams.pixelExpected shouldEqual false
      cookieParams.doNotTrack shouldEqual false
      cookieParams.contentType shouldEqual Some("application/json")

      response.status must beEqualTo(Status.Ok)
      response.bodyText.compile.string.unsafeRunSync() must beEqualTo("cookie")
    }

    "respond to the get or head cookie route with the cookie response" in {
      def test(method: Method) = {
        val (collectorService, routes) = createTestServices

        val request  = Request[IO](method = method, uri = uri"/p3/p4").withEntity("testBody")
        val response = routes.run(request).unsafeRunSync()

        val List(cookieParams) = collectorService.getCookieCalls
        cookieParams.body.unsafeRunSync() shouldEqual None
        cookieParams.path shouldEqual "/p1/p2"
        cookieParams.cookie shouldEqual None
        cookieParams.pixelExpected shouldEqual true
        cookieParams.doNotTrack shouldEqual false
        cookieParams.contentType shouldEqual None

        response.status must beEqualTo(Status.Ok)
        response.bodyText.compile.string.unsafeRunSync() must beEqualTo("cookie")
      }

      test(Method.GET)
      test(Method.HEAD)
    }

    "respond to the get or head pixel route with the cookie response" in {
      def test(method: Method, uri: String) = {
        val (collectorService, routes) = createTestServices

        val request  = Request[IO](method = method, uri = Uri.unsafeFromString(uri)).withEntity("testBody")
        val response = routes.run(request).unsafeRunSync()

        val List(cookieParams) = collectorService.getCookieCalls
        cookieParams.body.unsafeRunSync() shouldEqual None
        cookieParams.path shouldEqual uri
        cookieParams.cookie shouldEqual None
        cookieParams.pixelExpected shouldEqual true
        cookieParams.doNotTrack shouldEqual false
        cookieParams.contentType shouldEqual None

        response.status must beEqualTo(Status.Ok)
        response.bodyText.compile.string.unsafeRunSync() must beEqualTo("cookie")
      }

      test(Method.GET, "/i")
      test(Method.HEAD, "/i")
      test(Method.GET, "/ice.png")
      test(Method.HEAD, "/ice.png")
    }
  }

}
