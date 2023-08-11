package com.snowplowanalytics.snowplow.collectors.scalastream

import scala.concurrent.duration._
import scala.collection.JavaConverters._
import cats.effect.{Clock, IO}
import cats.effect.unsafe.implicits.global
import cats.data.NonEmptyList
import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload
import org.http4s._
import org.http4s.headers._
import org.http4s.implicits._
import org.typelevel.ci._
import com.comcast.ip4s.{IpAddress, SocketAddress}
import org.specs2.mutable.Specification
import com.snowplowanalytics.snowplow.collectors.scalastream.model._
import org.apache.thrift.{TDeserializer, TSerializer}

class CollectorServiceSpec extends Specification {
  case class ProbeService(service: CollectorService[IO], good: TestSink, bad: TestSink)

  val service = new CollectorService[IO](
    config     = TestUtils.testConf,
    sinks      = CollectorSinks[IO](new TestSink, new TestSink),
    appName    = "appName",
    appVersion = "appVersion"
  )
  val event     = new CollectorPayload("iglu-schema", "ip", System.currentTimeMillis, "UTF-8", "collector")
  val uuidRegex = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}".r
  val testHeaders = Headers(
    `User-Agent`(ProductId("testUserAgent")),
    Referer(Uri.unsafeFromString("example.com")),
    `Content-Type`(MediaType.application.json),
    `X-Forwarded-For`(IpAddress.fromString("127.0.0.1")),
    Cookie(RequestCookie("cookie", "value")),
    `Access-Control-Allow-Credentials`()
  )
  val testConnection = Request.Connection(
    local  = SocketAddress.fromStringIp("127.0.0.1:80").get,
    remote = SocketAddress.fromStringIp("127.0.0.1:80").get,
    secure = false
  )

  def probeService(): ProbeService = {
    val good = new TestSink
    val bad  = new TestSink
    val service = new CollectorService[IO](
      config     = TestUtils.testConf,
      sinks      = CollectorSinks[IO](good, bad),
      appName    = "appName",
      appVersion = "appVersion"
    )
    ProbeService(service, good, bad)
  }

  def emptyCollectorPayload: CollectorPayload =
    new CollectorPayload(null, null, System.currentTimeMillis, null, null)

  def serializer   = new TSerializer()
  def deserializer = new TDeserializer()

  "The collector service" should {
    "cookie" in {
      "not set a cookie if SP-Anonymous is present" in {
        val request = Request[IO]().withHeaders(Header.Raw(ci"SP-Anonymous", "*"))
        val r = service
          .cookie(
            body          = IO.pure(Some("b")),
            path          = "p",
            cookie        = None,
            request       = request,
            pixelExpected = false,
            doNotTrack    = false,
            contentType   = None
          )
          .unsafeRunSync()
        r.headers.get(ci"Set-Cookie") must beNone
      }
      "respond with a 200 OK and a good row in good sink" in {
        val ProbeService(service, good, bad) = probeService()
        val req = Request[IO](
          method  = Method.POST,
          headers = testHeaders,
          uri     = Uri(query = Query.unsafeFromString("a=b"))
        ).withAttribute(Request.Keys.ConnectionInfo, testConnection)
        val r = service
          .cookie(
            body          = IO.pure(Some("b")),
            path          = "p",
            cookie        = None,
            request       = req,
            pixelExpected = false,
            doNotTrack    = false,
            contentType   = Some("image/gif")
          )
          .unsafeRunSync()

        r.status mustEqual Status.Ok
        good.storedRawEvents must have size 1
        bad.storedRawEvents must have size 0

        val e = emptyCollectorPayload
        deserializer.deserialize(e, good.storedRawEvents.head)
        e.schema shouldEqual "iglu:com.snowplowanalytics.snowplow/CollectorPayload/thrift/1-0-0"
        e.ipAddress shouldEqual "127.0.0.1"
        e.encoding shouldEqual "UTF-8"
        e.collector shouldEqual s"appName-appVersion"
        e.querystring shouldEqual "a=b"
        e.body shouldEqual "b"
        e.path shouldEqual "p"
        e.userAgent shouldEqual "testUserAgent"
        e.refererUri shouldEqual "example.com"
        e.hostname shouldEqual "localhost"
        //e.networkUserId shouldEqual "nuid" //TODO: add check for nuid as well
        e.headers shouldEqual List(
          "User-Agent: testUserAgent",
          "Referer: example.com",
          "Content-Type: application/json",
          "X-Forwarded-For: 127.0.0.1",
          "Cookie: cookie=value",
          "Access-Control-Allow-Credentials: true",
          "image/gif"
        ).asJava
        e.contentType shouldEqual "image/gif"
      }

      "sink event with headers removed when spAnonymous set" in {
        val ProbeService(service, good, bad) = probeService()

        val req = Request[IO](
          method  = Method.POST,
          headers = testHeaders.put(Header.Raw(ci"SP-Anonymous", "*"))
        )
        val r = service
          .cookie(
            body          = IO.pure(Some("b")),
            path          = "p",
            cookie        = None,
            request       = req,
            pixelExpected = false,
            doNotTrack    = false,
            contentType   = Some("image/gif")
          )
          .unsafeRunSync()

        r.status mustEqual Status.Ok
        good.storedRawEvents must have size 1
        bad.storedRawEvents must have size 0

        val e = emptyCollectorPayload
        deserializer.deserialize(e, good.storedRawEvents.head)
        e.headers shouldEqual List(
          "User-Agent: testUserAgent",
          "Referer: example.com",
          "Content-Type: application/json",
          "Access-Control-Allow-Credentials: true",
          "SP-Anonymous: *",
          "image/gif"
        ).asJava
      }

      "return necessary cache control headers and respond with pixel when pixelExpected is true" in {
        val r = service
          .cookie(
            body          = IO.pure(Some("b")),
            path          = "p",
            cookie        = None,
            request       = Request[IO](),
            pixelExpected = true,
            doNotTrack    = false,
            contentType   = None
          )
          .unsafeRunSync()
        r.headers.get[`Cache-Control`] shouldEqual Some(
          `Cache-Control`(CacheDirective.`no-cache`(), CacheDirective.`no-store`, CacheDirective.`must-revalidate`)
        )
        r.body.compile.toList.unsafeRunSync().toArray shouldEqual CollectorService.pixel
      }

      "include CORS headers in the response" in {
        val r = service
          .cookie(
            body          = IO.pure(Some("b")),
            path          = "p",
            cookie        = None,
            request       = Request[IO](),
            pixelExpected = true,
            doNotTrack    = false,
            contentType   = None
          )
          .unsafeRunSync()
        r.headers.get[`Access-Control-Allow-Credentials`] shouldEqual Some(
          `Access-Control-Allow-Credentials`()
        )
        r.headers.get(ci"Access-Control-Allow-Origin").map(_.head) shouldEqual Some(
          Header.Raw(ci"Access-Control-Allow-Origin", "*")
        )
      }

      "include the origin if given to CORS headers in the response" in {
        val headers = Headers(
          Origin
            .HostList(
              NonEmptyList.of(
                Origin.Host(scheme = Uri.Scheme.http, host = Uri.Host.unsafeFromString("origin.com")),
                Origin.Host(
                  scheme = Uri.Scheme.http,
                  host   = Uri.Host.unsafeFromString("otherorigin.com"),
                  port   = Some(8080)
                )
              )
            )
            .asInstanceOf[Origin]
        )
        val request = Request[IO](headers = headers)
        val r = service
          .cookie(
            body          = IO.pure(Some("b")),
            path          = "p",
            cookie        = None,
            request       = request,
            pixelExpected = true,
            doNotTrack    = false,
            contentType   = None
          )
          .unsafeRunSync()
        r.headers.get[`Access-Control-Allow-Credentials`] shouldEqual Some(
          `Access-Control-Allow-Credentials`()
        )
        r.headers.get(ci"Access-Control-Allow-Origin").map(_.head) shouldEqual Some(
          Header.Raw(ci"Access-Control-Allow-Origin", "http://origin.com")
        )
      }
    }

    "preflightResponse" in {
      "return a response appropriate to cors preflight options requests" in {
        val expected = Headers(
          Header.Raw(ci"Access-Control-Allow-Origin", "*"),
          `Access-Control-Allow-Credentials`(),
          `Access-Control-Allow-Headers`(ci"Content-Type", ci"SP-Anonymous"),
          `Access-Control-Max-Age`.Cache(60).asInstanceOf[`Access-Control-Max-Age`]
        )
        service.preflightResponse(Request[IO]()).unsafeRunSync.headers shouldEqual expected
      }
    }

    "buildEvent" in {
      "fill the correct values" in {
        val ct      = Some("image/gif")
        val headers = List("X-Forwarded-For", "X-Real-Ip")
        val e = service.buildEvent(
          Some("q"),
          Some("b"),
          "p",
          Some("ua"),
          Some("ref"),
          Some("h"),
          "ip",
          "nuid",
          ct,
          headers
        )
        e.schema shouldEqual "iglu:com.snowplowanalytics.snowplow/CollectorPayload/thrift/1-0-0"
        e.ipAddress shouldEqual "ip"
        e.encoding shouldEqual "UTF-8"
        e.collector shouldEqual s"appName-appVersion"
        e.querystring shouldEqual "q"
        e.body shouldEqual "b"
        e.path shouldEqual "p"
        e.userAgent shouldEqual "ua"
        e.refererUri shouldEqual "ref"
        e.hostname shouldEqual "h"
        e.networkUserId shouldEqual "nuid"
        e.headers shouldEqual (headers ::: ct.toList).asJava
        e.contentType shouldEqual ct.get
      }

      "set fields to null if they aren't set" in {
        val headers = List()
        val e = service.buildEvent(
          None,
          None,
          "p",
          None,
          None,
          None,
          "ip",
          "nuid",
          None,
          headers
        )
        e.schema shouldEqual "iglu:com.snowplowanalytics.snowplow/CollectorPayload/thrift/1-0-0"
        e.ipAddress shouldEqual "ip"
        e.encoding shouldEqual "UTF-8"
        e.collector shouldEqual s"appName-appVersion"
        e.querystring shouldEqual null
        e.body shouldEqual null
        e.path shouldEqual "p"
        e.userAgent shouldEqual null
        e.refererUri shouldEqual null
        e.hostname shouldEqual null
        e.networkUserId shouldEqual "nuid"
        e.headers shouldEqual headers.asJava
        e.contentType shouldEqual null
      }
    }

    "sinkEvent" in {
      "send back the produced events" in {
        val ProbeService(s, good, bad) = probeService()
        s.sinkEvent(event, "key").unsafeRunSync()
        good.storedRawEvents must have size 1
        bad.storedRawEvents must have size 0
        good.storedRawEvents.head.zip(serializer.serialize(event)).forall { case (a, b) => a mustEqual b }
      }
    }

    "buildHttpResponse" in {
      "send back a gif if pixelExpected is true" in {
        val res = service.buildHttpResponse(testHeaders, pixelExpected = true)
        res.status shouldEqual Status.Ok
        res.headers shouldEqual testHeaders.put(`Content-Type`(MediaType.image.gif))
        res.body.compile.toList.unsafeRunSync().toArray shouldEqual CollectorService.pixel
      }
      "send back ok otherwise" in {
        val res = service.buildHttpResponse(testHeaders, pixelExpected = false)
        res.status shouldEqual Status.Ok
        res.headers shouldEqual testHeaders
        res.bodyText.compile.toList.unsafeRunSync() shouldEqual List("ok")
      }
    }

    "ipAndPartitionkey" in {
      "give back the ip and partition key as ip if remote address is defined" in {
        val address = Some("127.0.0.1")
        service.ipAndPartitionKey(address, true) shouldEqual (("127.0.0.1", "127.0.0.1"))
      }
      "give back the ip and a uuid as partition key if ipAsPartitionKey is false" in {
        val address    = Some("127.0.0.1")
        val (ip, pkey) = service.ipAndPartitionKey(address, false)
        ip shouldEqual "127.0.0.1"
        pkey must beMatching(uuidRegex)
      }
      "give back unknown as ip and a random uuid as partition key if the address isn't known" in {
        val (ip, pkey) = service.ipAndPartitionKey(None, true)
        ip shouldEqual "unknown"
        pkey must beMatching(uuidRegex)
      }
    }

    "cookieHeader" in {
      val testCookieConfig = CookieConfig(
        enabled        = true,
        name           = "name",
        expiration     = 5.seconds,
        domains        = List("domain"),
        fallbackDomain = None,
        secure         = false,
        httpOnly       = false,
        sameSite       = None
      )
      val now = Clock[IO].realTime.unsafeRunSync()

      "give back a cookie header with the appropriate configuration" in {
        val nuid = "nuid"
        val conf = testCookieConfig
        val Some(`Set-Cookie`(cookie)) = service.cookieHeader(
          headers       = Headers.empty,
          cookieConfig  = Some(conf),
          networkUserId = nuid,
          doNotTrack    = false,
          spAnonymous   = None,
          now           = now
        )

        cookie.name shouldEqual conf.name
        cookie.content shouldEqual nuid
        cookie.domain shouldEqual None
        cookie.path shouldEqual Some("/")
        cookie.expires must beSome
        (cookie.expires.get.toDuration - now).toMillis must beCloseTo(conf.expiration.toMillis, 1000L)
        cookie.secure must beFalse
        cookie.httpOnly must beFalse
        cookie.extension must beEmpty
      }
      "give back None if no configuration is given" in {
        service.cookieHeader(
          headers       = Headers.empty,
          cookieConfig  = None,
          networkUserId = "nuid",
          doNotTrack    = false,
          spAnonymous   = None,
          now           = now
        ) shouldEqual None
      }
      "give back None if doNoTrack is true" in {
        val conf = testCookieConfig
        service.cookieHeader(
          headers       = Headers.empty,
          cookieConfig  = Some(conf),
          networkUserId = "nuid",
          doNotTrack    = true,
          spAnonymous   = None,
          now           = now
        ) shouldEqual None
      }
      "give back None if SP-Anonymous header is present" in {
        val conf = testCookieConfig
        service.cookieHeader(
          headers       = Headers.empty,
          cookieConfig  = Some(conf),
          networkUserId = "nuid",
          doNotTrack    = true,
          spAnonymous   = Some("*"),
          now           = now
        ) shouldEqual None
      }
      "give back a cookie header with Secure, HttpOnly and SameSite=None" in {
        val nuid = "nuid"
        val conf = testCookieConfig.copy(
          secure   = true,
          httpOnly = true,
          sameSite = Some(SameSite.None)
        )
        val Some(`Set-Cookie`(cookie)) =
          service.cookieHeader(
            headers       = Headers.empty,
            cookieConfig  = Some(conf),
            networkUserId = nuid,
            doNotTrack    = false,
            spAnonymous   = None,
            now           = now
          )
        cookie.secure must beTrue
        cookie.httpOnly must beTrue
        cookie.sameSite must beSome(SameSite.None)
        cookie.extension must beNone
        service.cookieHeader(
          headers       = Headers.empty,
          cookieConfig  = Some(conf),
          networkUserId = nuid,
          doNotTrack    = true,
          spAnonymous   = None,
          now           = now
        ) shouldEqual None
      }
    }

    "accessControlAllowOriginHeader" in {
      "give a restricted ACAO header if there is an Origin header in the request" in {
        val headers = Headers(
          Origin
            .HostList(
              NonEmptyList.of(
                Origin.Host(scheme = Uri.Scheme.http, host = Uri.Host.unsafeFromString("origin.com"))
              )
            )
            .asInstanceOf[Origin]
        )
        val request  = Request[IO](headers = headers)
        val expected = Header.Raw(ci"Access-Control-Allow-Origin", "http://origin.com")
        service.accessControlAllowOriginHeader(request) shouldEqual expected
      }
      "give a restricted ACAO header if there are multiple Origin headers in the request" in {
        val headers = Headers(
          Origin
            .HostList(
              NonEmptyList.of(
                Origin.Host(scheme = Uri.Scheme.http, host = Uri.Host.unsafeFromString("origin.com")),
                Origin.Host(
                  scheme = Uri.Scheme.http,
                  host   = Uri.Host.unsafeFromString("otherorigin.com"),
                  port   = Some(8080)
                )
              )
            )
            .asInstanceOf[Origin]
        )
        val request  = Request[IO](headers = headers)
        val expected = Header.Raw(ci"Access-Control-Allow-Origin", "http://origin.com")
        service.accessControlAllowOriginHeader(request) shouldEqual expected
      }
      "give an open ACAO header if there are no Origin headers in the request" in {
        val expected = Header.Raw(ci"Access-Control-Allow-Origin", "*")
        service.accessControlAllowOriginHeader(Request[IO]()) shouldEqual expected
      }
    }

    "cookieDomain" in {
      val testCookieConfig = CookieConfig(
        enabled        = true,
        name           = "name",
        expiration     = 5.seconds,
        domains        = List.empty,
        fallbackDomain = None,
        secure         = false,
        httpOnly       = false,
        sameSite       = None
      )
      "not return a domain" in {
        "if a list of domains is not supplied in the config and there is no fallback domain" in {
          val headers      = Headers.empty
          val cookieConfig = testCookieConfig
          service.cookieDomain(headers, cookieConfig.domains, cookieConfig.fallbackDomain) shouldEqual None
        }
        "if a list of domains is supplied in the config but the Origin request header is empty and there is no fallback domain" in {
          val headers      = Headers.empty
          val cookieConfig = testCookieConfig.copy(domains = List("domain.com"))
          service.cookieDomain(headers, cookieConfig.domains, cookieConfig.fallbackDomain) shouldEqual None
        }
        "if none of the domains in the request's Origin header has a match in the list of domains supplied with the config and there is no fallback domain" in {
          val origin: Origin = Origin.HostList(
            NonEmptyList.of(
              Origin.Host(scheme = Uri.Scheme.http, host = Uri.Host.unsafeFromString("origin.com")),
              Origin
                .Host(scheme = Uri.Scheme.http, host = Uri.Host.unsafeFromString("otherorigin.com"), port = Some(8080))
            )
          )
          val headers = Headers(origin.toRaw1)
          val cookieConfig = testCookieConfig.copy(
            domains = List("domain.com", "otherdomain.com")
          )
          service.cookieDomain(headers, cookieConfig.domains, cookieConfig.fallbackDomain) shouldEqual None
        }
      }
      "return the fallback domain" in {
        "if a list of domains is not supplied in the config but a fallback domain is configured" in {
          val headers = Headers.empty
          val cookieConfig = testCookieConfig.copy(
            fallbackDomain = Some("fallbackDomain")
          )
          service.cookieDomain(headers, cookieConfig.domains, cookieConfig.fallbackDomain) shouldEqual Some(
            "fallbackDomain"
          )
        }
        "if the Origin header is empty and a fallback domain is configured" in {
          val headers = Headers.empty
          val cookieConfig = testCookieConfig.copy(
            domains        = List("domain.com"),
            fallbackDomain = Some("fallbackDomain")
          )
          service.cookieDomain(headers, cookieConfig.domains, cookieConfig.fallbackDomain) shouldEqual Some(
            "fallbackDomain"
          )
        }
        "if none of the domains in the request's Origin header has a match in the list of domains supplied with the config but a fallback domain is configured" in {
          val origin: Origin = Origin.HostList(
            NonEmptyList.of(
              Origin.Host(scheme = Uri.Scheme.http, host = Uri.Host.unsafeFromString("origin.com")),
              Origin
                .Host(scheme = Uri.Scheme.http, host = Uri.Host.unsafeFromString("otherorigin.com"), port = Some(8080))
            )
          )
          val headers = Headers(origin.toRaw1)
          val cookieConfig = testCookieConfig.copy(
            domains        = List("domain.com", "otherdomain.com"),
            fallbackDomain = Some("fallbackDomain")
          )
          service.cookieDomain(headers, cookieConfig.domains, cookieConfig.fallbackDomain) shouldEqual Some(
            "fallbackDomain"
          )
        }
      }
      "return the matched domain" in {
        "if there is only one domain in the request's Origin header and it matches in the list of domains supplied with the config" in {
          val origin: Origin = Origin.HostList(
            NonEmptyList.of(
              Origin.Host(scheme = Uri.Scheme.http, host = Uri.Host.unsafeFromString("www.domain.com"))
            )
          )
          val headers = Headers(origin.toRaw1)
          val cookieConfig = testCookieConfig.copy(
            domains        = List("domain.com", "otherdomain.com"),
            fallbackDomain = Some("fallbackDomain")
          )
          service.cookieDomain(headers, cookieConfig.domains, cookieConfig.fallbackDomain) shouldEqual Some(
            "domain.com"
          )
        }
        "if multiple domains from the request's Origin header have matches in the list of domains supplied with the config" in {
          val origin: Origin = Origin.HostList(
            NonEmptyList.of(
              Origin.Host(scheme = Uri.Scheme.http, host = Uri.Host.unsafeFromString("www.domain2.com")),
              Origin.Host(scheme = Uri.Scheme.http, host = Uri.Host.unsafeFromString("www.domain.com")),
              Origin.Host(
                scheme = Uri.Scheme.http,
                host   = Uri.Host.unsafeFromString("www.otherdomain.com"),
                port   = Some(8080)
              )
            )
          )
          val headers = Headers(origin.toRaw1)
          val cookieConfig = testCookieConfig.copy(
            domains        = List("domain.com", "otherdomain.com"),
            fallbackDomain = Some("fallbackDomain")
          )
          service.cookieDomain(headers, cookieConfig.domains, cookieConfig.fallbackDomain) shouldEqual Some(
            "domain.com"
          )
        }
      }
    }

    "extractHosts" in {
      "correctly extract the host names from a list of values in the request's Origin header" in {
        val originHostList = NonEmptyList.of(
          Origin.Host(scheme = Uri.Scheme.https, host = Uri.Host.unsafeFromString("origin.com")),
          Origin.Host(
            scheme = Uri.Scheme.http,
            host   = Uri.Host.unsafeFromString("subdomain.otherorigin.gov.co.uk"),
            port   = Some(8080)
          )
        )
        val origin: Origin = Origin.HostList(originHostList)
        val headers        = Headers(origin.toRaw1)
        service.extractHostsFromOrigin(headers) shouldEqual originHostList.toList
      }
    }

    "validMatch" in {
      val domain = "snplow.com"
      "true for valid matches" in {
        val validHost1 = "snplow.com"
        val validHost2 = "blog.snplow.com"
        val validHost3 = "blog.snplow.com.snplow.com"
        service.validMatch(validHost1, domain) shouldEqual true
        service.validMatch(validHost2, domain) shouldEqual true
        service.validMatch(validHost3, domain) shouldEqual true
      }
      "false for invalid matches" in {
        val invalidHost1 = "notsnplow.com"
        val invalidHost2 = "blog.snplow.comsnplow.com"
        service.validMatch(invalidHost1, domain) shouldEqual false
        service.validMatch(invalidHost2, domain) shouldEqual false
      }
    }

    "determinePath" in {
      val vendor   = "com.acme"
      val version1 = "track"
      val version2 = "redirect"
      val version3 = "iglu"

      "should correctly replace the path in the request if a mapping is provided" in {
        val expected1 = "/com.snowplowanalytics.snowplow/tp2"
        val expected2 = "/r/tp2"
        val expected3 = "/com.snowplowanalytics.iglu/v1"

        service.determinePath(vendor, version1) shouldEqual expected1
        service.determinePath(vendor, version2) shouldEqual expected2
        service.determinePath(vendor, version3) shouldEqual expected3
      }

      "should pass on the original path if no mapping for it can be found" in {
        val service = new CollectorService(
          TestUtils.testConf.copy(paths = Map.empty[String, String]),
          CollectorSinks(new TestSink, new TestSink),
          "",
          ""
        )
        val expected1 = "/com.acme/track"
        val expected2 = "/com.acme/redirect"
        val expected3 = "/com.acme/iglu"

        service.determinePath(vendor, version1) shouldEqual expected1
        service.determinePath(vendor, version2) shouldEqual expected2
        service.determinePath(vendor, version3) shouldEqual expected3
      }
    }
  }
}
