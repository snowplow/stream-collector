package com.snowplowanalytics.snowplow.collector.core

import scala.concurrent.duration._

import cats.Applicative

import org.http4s.SameSite

import com.snowplowanalytics.snowplow.collector.core.Config._

object TestUtils {
  val appName    = "collector-test"
  val appVersion = "testVersion"

  val appInfo = new AppInfo {
    def name        = appName
    def version     = appVersion
    def dockerAlias = "docker run collector"
  }

  def noopSink[F[_]: Applicative]: Sink[F] = new Sink[F] {
    val maxBytes: Int                                                   = Int.MaxValue
    def isHealthy: F[Boolean]                                           = Applicative[F].pure(true)
    def storeRawEvents(events: List[Array[Byte]], key: String): F[Unit] = Applicative[F].unit
  }

  val testConfig = Config[Any](
    interface = "0.0.0.0",
    port      = 8080,
    paths = Map(
      "/com.acme/track"    -> "/com.snowplowanalytics.snowplow/tp2",
      "/com.acme/redirect" -> "/r/tp2",
      "/com.acme/iglu"     -> "/com.snowplowanalytics.iglu/v1"
    ),
    p3p = P3P(
      "/w3c/p3p.xml",
      "NOI DSP COR NID PSA OUR IND COM NAV STA"
    ),
    crossDomain = CrossDomain(
      false,
      List("*"),
      true
    ),
    cookie = Cookie(
      enabled        = true,
      name           = "sp",
      expiration     = 365.days,
      domains        = Nil,
      fallbackDomain = None,
      secure         = true,
      httpOnly       = true,
      sameSite       = Some(SameSite.None)
    ),
    doNotTrackCookie = DoNotTrackCookie(
      false,
      "",
      ""
    ),
    cookieBounce = CookieBounce(
      false,
      "n3pc",
      "00000000-0000-4000-A000-000000000000",
      None
    ),
    redirectMacro = RedirectMacro(
      false,
      None
    ),
    rootResponse = RootResponse(
      false,
      302,
      Map.empty[String, String],
      ""
    ),
    cors = CORS(60.seconds),
    streams = Streams(
      "raw",
      "bad-1",
      false,
      AnyRef,
      Buffer(
        3145728,
        500,
        5000
      )
    ),
    monitoring = Monitoring(
      Metrics(
        Statsd(
          false,
          "localhost",
          8125,
          10.seconds,
          "snowplow.collector"
        )
      )
    ),
    ssl = SSL(
      false,
      false,
      443
    ),
    enableDefaultRedirect = false,
    redirectDomains       = Set.empty[String],
    preTerminationPeriod  = 10.seconds
  )
}
