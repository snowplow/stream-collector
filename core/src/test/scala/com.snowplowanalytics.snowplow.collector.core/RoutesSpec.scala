package com.snowplowanalytics.snowplow.collector.core

import scala.collection.mutable.ListBuffer

import org.specs2.mutable.Specification

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import org.http4s.implicits._
import org.http4s._
import org.http4s.headers._
import org.http4s.Status._

import fs2.{Stream, text}

class RoutesSpec extends Specification {

  case class CookieParams(
    body: IO[Option[String]],
    path: String,
    request: Request[IO],
    pixelExpected: Boolean,
    doNotTrack: Boolean,
    contentType: Option[String]
  )

  class TestService() extends IService[IO] {

    private val cookieCalls: ListBuffer[CookieParams] = ListBuffer()

    def getCookieCalls: List[CookieParams] = cookieCalls.toList

    override def preflightResponse(req: Request[IO]): IO[Response[IO]] =
      IO.pure(Response[IO](status = Ok, body = Stream.emit("preflight response").through(text.utf8.encode)))

    override def cookie(
      body: IO[Option[String]],
      path: String,
      request: Request[IO],
      pixelExpected: Boolean,
      doNotTrack: Boolean,
      contentType: Option[String]
    ): IO[Response[IO]] =
      IO.delay {
        cookieCalls += CookieParams(
          body,
          path,
          request,
          pixelExpected,
          doNotTrack,
          contentType
        )
        Response(status = Ok, body = Stream.emit("cookie").through(text.utf8.encode))
      }

    override def determinePath(vendor: String, version: String): String = s"/$vendor/$version"

    override def sinksHealthy: IO[Boolean] = IO.pure(true)
  }

  def createTestServices(enabledDefaultRedirect: Boolean = true) = {
    val service = new TestService()
    val routes  = new Routes(enabledDefaultRedirect, service).value
    (service, routes)
  }

  "The collector route" should {
    "respond to the health route with an ok response" in {
      val (_, routes) = createTestServices()
      val request     = Request[IO](method = Method.GET, uri = uri"/health")
      val response    = routes.run(request).unsafeRunSync()

      response.status must beEqualTo(Status.Ok)
      response.as[String].unsafeRunSync() must beEqualTo("ok")
    }

    "respond to the cors route with a preflight response" in {
      val (_, routes) = createTestServices()
      def test(uri: Uri) = {
        val request  = Request[IO](method = Method.OPTIONS, uri = uri)
        val response = routes.run(request).unsafeRunSync()
        response.as[String].unsafeRunSync() shouldEqual "preflight response"
      }
      test(uri"/i")
      test(uri"/health")
      test(uri"/p3/p4")
    }

    "respond to the post cookie route with the cookie response" in {
      val (collectorService, routes) = createTestServices()

      val request = Request[IO](method = Method.POST, uri = uri"/p3/p4")
        .withEntity("testBody")
        .withHeaders(`Content-Type`(MediaType.application.json))
      val response = routes.run(request).unsafeRunSync()

      val List(cookieParams) = collectorService.getCookieCalls
      cookieParams.body.unsafeRunSync() shouldEqual Some("testBody")
      cookieParams.path shouldEqual "/p3/p4"
      cookieParams.pixelExpected shouldEqual false
      cookieParams.doNotTrack shouldEqual false
      cookieParams.contentType shouldEqual Some("application/json")

      response.status must beEqualTo(Status.Ok)
      response.bodyText.compile.string.unsafeRunSync() must beEqualTo("cookie")
    }

    "respond to the get or head cookie route with the cookie response" in {
      def test(method: Method) = {
        val (collectorService, routes) = createTestServices()

        val request  = Request[IO](method = method, uri = uri"/p3/p4").withEntity("testBody")
        val response = routes.run(request).unsafeRunSync()

        val List(cookieParams) = collectorService.getCookieCalls
        cookieParams.body.unsafeRunSync() shouldEqual None
        cookieParams.path shouldEqual "/p3/p4"
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
        val (collectorService, routes) = createTestServices()

        val request  = Request[IO](method = method, uri = Uri.unsafeFromString(uri)).withEntity("testBody")
        val response = routes.run(request).unsafeRunSync()

        val List(cookieParams) = collectorService.getCookieCalls
        cookieParams.body.unsafeRunSync() shouldEqual None
        cookieParams.path shouldEqual uri
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

    "respond to the iglu webhook with the cookie response" in {
      def test(method: Method, uri: Uri, contentType: Option[`Content-Type`], body: Option[String]) = {
        val (collectorService, routes) = createTestServices()

        val request  = Request[IO](method, uri).withEntity(body.getOrElse("")).withContentTypeOption(contentType)
        val response = routes.run(request).unsafeRunSync()

        val List(cookieParams) = collectorService.getCookieCalls
        cookieParams.body.unsafeRunSync() shouldEqual body
        cookieParams.path shouldEqual uri.path.renderString
        method match {
          case Method.POST =>
            for {
              actual   <- cookieParams.contentType
              expected <- contentType
            } yield `Content-Type`.parse(actual) must beRight(expected)
          case Method.GET =>
            cookieParams.pixelExpected shouldEqual true
            cookieParams.contentType shouldEqual None
          case other =>
            ko(s"Invalid http method - $other")
        }
        cookieParams.doNotTrack shouldEqual false
        response.status must beEqualTo(Status.Ok)
        response.bodyText.compile.string.unsafeRunSync() must beEqualTo("cookie")
      }

      val jsonBody = """{ "network": "twitter", "action": "retweet" }"""
      val sdjBody  = s"""{
                       |   "schema":"iglu:com.snowplowanalytics.snowplow/social_interaction/jsonschema/1-0-0",
                       |   "data": $jsonBody
                       |}""".stripMargin

      test(
        Method.POST,
        uri"/com.snowplowanalytics.iglu/v1",
        Some(`Content-Type`(MediaType.application.json): `Content-Type`),
        Some(sdjBody)
      )
      test(
        Method.POST,
        uri"/com.snowplowanalytics.iglu/v1?schema=iglu%3Acom.snowplowanalytics.snowplow%2Fsocial_interaction%2Fjsonschema%2F1-0-0",
        Some(`Content-Type`(MediaType.application.json).withCharset(Charset.`UTF-8`)),
        Some(jsonBody)
      )
      test(
        Method.POST,
        uri"/com.snowplowanalytics.iglu/v1?schema=iglu%3Acom.snowplowanalytics.snowplow%2Fsocial_interaction%2Fjsonschema%2F1-0-0",
        Some(`Content-Type`(MediaType.application.`x-www-form-urlencoded`)),
        Some("network=twitter&action=retweet")
      )
      test(
        Method.GET,
        uri"""/com.snowplowanalytics.iglu/v1?schema=iglu%3Acom.snowplowanalytics.snowplow%2Fsocial_interaction%2Fjsonschema%2F1-0-0&aid=mobile-attribution&p=mob&network=twitter&action=retweet""",
        None,
        None
      )
    }

    "allow redirect routes when redirects enabled" in {
      def test(method: Method) = {
        val (_, routes) = createTestServices()

        val request  = Request[IO](method = method, uri = uri"/r/abc")
        val response = routes.run(request).unsafeRunSync()

        response.status must beEqualTo(Status.Ok)
        response.bodyText.compile.string.unsafeRunSync() must beEqualTo("cookie")
      }

      test(Method.GET)
      test(Method.POST)
    }

    "disallow redirect routes when redirects disabled" in {
      def test(method: Method) = {
        val (_, routes) = createTestServices(enabledDefaultRedirect = false)

        val request  = Request[IO](method = method, uri = uri"/r/abc")
        val response = routes.run(request).unsafeRunSync()

        response.status must beEqualTo(Status.NotFound)
        response.bodyText.compile.string.unsafeRunSync() must beEqualTo("redirects disabled")
      }

      test(Method.GET)
      test(Method.POST)
    }
  }

}
