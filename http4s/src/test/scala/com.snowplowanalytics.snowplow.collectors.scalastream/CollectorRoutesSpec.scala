package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.{Method, Request, Status}
import org.specs2.mutable.Specification

class CollectorRoutesSpec extends Specification {

  "Health endpoint" should {
    "return OK always because collector always works" in {
      val request  = Request[IO](method = Method.GET, uri = uri"/health")
      val response = new CollectorRoutes[IO].value.run(request).unsafeRunSync()

      response.status must beEqualTo(Status.Ok)
      response.as[String].unsafeRunSync() must beEqualTo("OK")
    }
  }

}
