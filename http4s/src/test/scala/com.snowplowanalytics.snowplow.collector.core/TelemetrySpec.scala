package com.snowplowanalytics.snowplow.collector.core

import scala.concurrent.duration._
import scala.collection.mutable.ListBuffer

import org.apache.commons.codec.binary.Base64
import org.apache.commons.codec.digest.DigestUtils

import java.nio.charset.StandardCharsets

import cats.effect._
import cats.effect.unsafe.implicits.global
import cats.effect.testkit.TestControl

import org.http4s._
import org.http4s.client.{Client => HttpClient}

import io.circe._
import io.circe.parser._
import io.circe.syntax._

import fs2.Stream

import com.snowplowanalytics.snowplow.scalatracker.emitters.http4s.ceTracking

import org.specs2.mutable.Specification

class TelemetrySpec extends Specification {

  case class ProbeTelemetry(
    telemetryStream: Stream[IO, Unit],
    telemetryEvents: ListBuffer[Json]
  )

  val appId                  = "testAppId"
  val region                 = Some("testRegion")
  val cloud                  = Some("testCloud")
  val unhashedInstallationId = Some("testInstallationId")
  val interval               = 5.minutes
  val telemetryConfig = Config.Telemetry(
    disable         = false,
    interval        = interval,
    method          = "POST",
    url             = "127.0.0.1",
    port            = 443,
    secure          = true,
    userProvidedId  = None,
    moduleName      = None,
    moduleVersion   = None,
    instanceId      = None,
    autoGeneratedId = None
  )

  def probeTelemetry(telemetryConfig: Config.Telemetry): ProbeTelemetry = {
    val telemetryEvents = ListBuffer[Json]()
    val mockHttpApp = HttpRoutes
      .of[IO] {
        case req =>
          IO {
            telemetryEvents += extractTelemetryEvent(req)
            Response[IO](status = Status.Ok)
          }
      }
      .orNotFound
    val mockClient     = HttpClient.fromHttpApp[IO](mockHttpApp)
    val telemetryInfoF = IO(Telemetry.TelemetryInfo(region, cloud, unhashedInstallationId))
    val telemetryStream = Telemetry.run[IO](
      telemetryConfig,
      mockClient,
      TestUtils.appInfo,
      appId,
      telemetryInfoF
    )
    ProbeTelemetry(telemetryStream, telemetryEvents)
  }

  def extractTelemetryEvent(req: Request[IO]): Json = {
    val body        = req.bodyText.compile.string.unsafeRunSync()
    val jsonBody    = parse(body).toOption.get
    val uepxEncoded = jsonBody.hcursor.downField("data").downN(0).downField("ue_px").as[String].toOption.get
    val uePxDecoded = new String(Base64.decodeBase64(uepxEncoded), StandardCharsets.UTF_8)
    parse(uePxDecoded).toOption.get.hcursor.downField("data").as[Json].toOption.get
  }

  def expectedEvent(config: Config.Telemetry): Json = {
    val installationId = unhashedInstallationId.map(DigestUtils.sha256Hex)
    Json.obj(
      "schema" -> "iglu:com.snowplowanalytics.oss/oss_context/jsonschema/1-0-2".asJson,
      "data" -> Json.obj(
        "userProvidedId"     -> config.userProvidedId.asJson,
        "autoGeneratedId"    -> config.autoGeneratedId.asJson,
        "moduleName"         -> config.moduleName.asJson,
        "moduleVersion"      -> config.moduleVersion.asJson,
        "instanceId"         -> config.instanceId.asJson,
        "appGeneratedId"     -> appId.asJson,
        "cloud"              -> cloud.asJson,
        "region"             -> region.asJson,
        "installationId"     -> installationId.asJson,
        "applicationName"    -> TestUtils.appInfo.name.asJson,
        "applicationVersion" -> TestUtils.appInfo.version.asJson
      )
    )
  }

  "Telemetry" should {
    "send correct number of events" in {
      val eventCount = 10
      val timeout    = (interval * eventCount.toLong) + 1.minutes
      val probe      = probeTelemetry(telemetryConfig)
      TestControl.executeEmbed(probe.telemetryStream.timeout(timeout).compile.drain.voidError).unsafeRunSync()
      val events   = probe.telemetryEvents
      val expected = (1 to eventCount).map(_ => expectedEvent(telemetryConfig)).toList
      events must beEqualTo(expected)
    }

    "not send any events if telemetry is disabled" in {
      val probe = probeTelemetry(telemetryConfig.copy(disable = true))
      TestControl
        .executeEmbed(
          probe.telemetryStream.timeout(interval * 10).compile.drain.voidError
        )
        .unsafeRunSync()
      probe.telemetryEvents must beEmpty
    }
  }
}
