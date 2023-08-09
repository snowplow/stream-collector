package com.snowplowanalytics.snowplow.collectors.scalastream

import scala.collection.mutable.ListBuffer
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.SocketAddress
import org.http4s.implicits._
import org.http4s._
import org.http4s.headers._
import org.http4s.Status._
import fs2.{Stream, text}
import org.typelevel.ci._
import org.specs2.mutable.Specification

class CollectorRoutesSpec extends Specification {

  case class CookieParams(
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
  )

  class TestService() extends Service[IO] {

    private val cookieCalls: ListBuffer[CookieParams] = ListBuffer()

    def getCookieCalls: List[CookieParams] = cookieCalls.toList

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
      IO.delay {
        cookieCalls += CookieParams(
          queryString,
          body,
          path,
          cookie,
          userAgent,
          refererUri,
          hostname,
          ip,
          request,
          pixelExpected,
          doNotTrack,
          contentType,
          spAnonymous
        )
        Response(status = Ok, body = Stream.emit("cookie").through(text.utf8.encode))
      }

    override def determinePath(vendor: String, version: String): String = "/p1/p2"
  }

  val testConnection = Request.Connection(
    local  = SocketAddress.fromStringIp("127.0.0.1:80").get,
    remote = SocketAddress.fromStringIp("127.0.0.1:80").get,
    secure = false
  )

  val testHeaders = Headers(
    `User-Agent`(ProductId("testUserAgent")),
    Referer(Uri.unsafeFromString("example.com")),
    Header.Raw(ci"SP-Anonymous", "*"),
    `Content-Type`(MediaType.application.json)
  )

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

      val request = Request[IO](method = Method.POST, uri = uri"/p3/p4?a=b&c=d")
        .withAttribute(Request.Keys.ConnectionInfo, testConnection)
        .withEntity("testBody")
        .withHeaders(testHeaders)
      val response = routes.run(request).unsafeRunSync()

      val List(cookieParams) = collectorService.getCookieCalls
      cookieParams.queryString shouldEqual Some("a=b&c=d")
      cookieParams.body.unsafeRunSync() shouldEqual Some("testBody")
      cookieParams.path shouldEqual "/p1/p2"
      cookieParams.cookie shouldEqual None
      cookieParams.userAgent shouldEqual Some("testUserAgent")
      cookieParams.refererUri shouldEqual Some("example.com")
      cookieParams.hostname.unsafeRunSync() shouldEqual Some("localhost")
      cookieParams.ip shouldEqual Some("127.0.0.1")
      cookieParams.pixelExpected shouldEqual false
      cookieParams.doNotTrack shouldEqual false
      cookieParams.contentType shouldEqual Some("application/json")
      cookieParams.spAnonymous shouldEqual Some("*")

      response.status must beEqualTo(Status.Ok)
      response.bodyText.compile.string.unsafeRunSync() must beEqualTo("cookie")
    }

    "respond to the get or head cookie route with the cookie response" in {
      def getHeadTest(method: Method) = {
        val (collectorService, routes) = createTestServices

        val request = Request[IO](method = method, uri = uri"/p3/p4?a=b&c=d")
          .withAttribute(Request.Keys.ConnectionInfo, testConnection)
          .withEntity("testBody")
          .withHeaders(testHeaders)
        val response = routes.run(request).unsafeRunSync()

        val List(cookieParams) = collectorService.getCookieCalls
        cookieParams.queryString shouldEqual Some("a=b&c=d")
        cookieParams.body.unsafeRunSync() shouldEqual None
        cookieParams.path shouldEqual "/p1/p2"
        cookieParams.cookie shouldEqual None
        cookieParams.userAgent shouldEqual Some("testUserAgent")
        cookieParams.refererUri shouldEqual Some("example.com")
        cookieParams.hostname.unsafeRunSync() shouldEqual Some("localhost")
        cookieParams.ip shouldEqual Some("127.0.0.1")
        cookieParams.pixelExpected shouldEqual true
        cookieParams.doNotTrack shouldEqual false
        cookieParams.contentType shouldEqual None
        cookieParams.spAnonymous shouldEqual Some("*")

        response.status must beEqualTo(Status.Ok)
        response.bodyText.compile.string.unsafeRunSync() must beEqualTo("cookie")
      }

      getHeadTest(Method.GET)
      getHeadTest(Method.HEAD)
    }
  }

}
