package com.snowplowanalytics.snowplow.collectors.scalastream

import scala.collection.JavaConverters._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload
import org.http4s.{Headers, Method, Request, RequestCookie, Status}
import org.http4s.headers._
import com.comcast.ip4s.IpAddress
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
      "respond with a 200 OK and a good row in good sink" in {
        val ProbeService(service, good, bad) = probeService()
        val headers = Headers(
          `X-Forwarded-For`(IpAddress.fromString("127.0.0.1")),
          Cookie(RequestCookie("cookie", "value")),
          `Access-Control-Allow-Credentials`()
        )
        val req = Request[IO](
          method  = Method.POST,
          headers = headers
        )
        val r = service
          .cookie(
            queryString   = Some("a=b"),
            body          = IO.pure(Some("b")),
            path          = "p",
            cookie        = None,
            userAgent     = Some("ua"),
            refererUri    = Some("ref"),
            hostname      = IO.pure(Some("h")),
            ip            = Some("ip"),
            request       = req,
            pixelExpected = false,
            doNotTrack    = false,
            contentType   = Some("image/gif"),
            spAnonymous   = None
          )
          .unsafeRunSync()

        r.status mustEqual Status.Ok
        good.storedRawEvents must have size 1
        bad.storedRawEvents must have size 0

        val e = emptyCollectorPayload
        deserializer.deserialize(e, good.storedRawEvents.head)
        e.schema shouldEqual "iglu:com.snowplowanalytics.snowplow/CollectorPayload/thrift/1-0-0"
        e.ipAddress shouldEqual "ip"
        e.encoding shouldEqual "UTF-8"
        e.collector shouldEqual s"appName-appVersion"
        e.querystring shouldEqual "a=b"
        e.body shouldEqual "b"
        e.path shouldEqual "p"
        e.userAgent shouldEqual "ua"
        e.refererUri shouldEqual "ref"
        e.hostname shouldEqual "h"
        //e.networkUserId shouldEqual "nuid" //TODO: add check for nuid as well
        e.headers shouldEqual List(
          "X-Forwarded-For: 127.0.0.1",
          "Cookie: cookie=value",
          "Access-Control-Allow-Credentials: true",
          "image/gif"
        ).asJava
        e.contentType shouldEqual "image/gif"
      }

      "sink event with headers removed when spAnonymous set" in {
        val ProbeService(service, good, bad) = probeService()
        val headers = Headers(
          `X-Forwarded-For`(IpAddress.fromString("127.0.0.1")),
          Cookie(RequestCookie("cookie", "value")),
          `Access-Control-Allow-Credentials`()
        )
        val req = Request[IO](
          method  = Method.POST,
          headers = headers
        )
        val r = service
          .cookie(
            queryString   = Some("a=b"),
            body          = IO.pure(Some("b")),
            path          = "p",
            cookie        = None,
            userAgent     = Some("ua"),
            refererUri    = Some("ref"),
            hostname      = IO.pure(Some("h")),
            ip            = Some("ip"),
            request       = req,
            pixelExpected = false,
            doNotTrack    = false,
            contentType   = Some("image/gif"),
            spAnonymous   = Some("*")
          )
          .unsafeRunSync()

        r.status mustEqual Status.Ok
        good.storedRawEvents must have size 1
        bad.storedRawEvents must have size 0

        val e = emptyCollectorPayload
        deserializer.deserialize(e, good.storedRawEvents.head)
        e.headers shouldEqual List(
          "Access-Control-Allow-Credentials: true",
          "image/gif"
        ).asJava
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
