package com.snowplowanalytics.snowplow.collectors.scalastream.e2e

import cats.effect.{ContextShift, IO, Resource, Timer}
import com.snowplowanalytics.snowplow.collectors.scalastream.e2e.storage.StorageTarget
import doobie.implicits._
import io.circe.Json
import io.circe.literal.JsonStringContext
import org.http4s._
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.circe._
import org.http4s.client.Client
import org.specs2.mutable.Specification
import retry.{RetryDetails, RetryPolicies, retryingOnFailures}

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

abstract class E2EScenarios extends Specification with StorageTarget {
  skipAllIf(anyEnvironmentVariableMissing())

  private lazy val collectorHostEnv = "TEST_COLLECTOR_HOST"
  private lazy val timeout          = 5.minutes

  implicit val ioContextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val ioTimer: Timer[IO]               = IO.timer(ExecutionContext.global)

  "Scenario 1" in {
    val appId = s"e2e-test-${UUID.randomUUID()}"
    val collectorPayload =
      json"""
         {
           "schema": "iglu:com.snowplowanalytics.snowplow/payload_data/jsonschema/1-0-4",
           "data": [
             {
               "e": "pv",
               "tv": "e2e-test",
               "aid": $appId,
               "p": "web"
             }
           ]
         }"""

    val request         = buildTP2CollectorRequest(collectorPayload)
    val expectedDbCount = 1

    val e2eScenario = for {
      collectorResponse <- executeHttpRequest(request)
      actualDbCount     <- waitUntilAllDataReachDB(appId, expectedDbCount)
    } yield {
      collectorResponse.status.code must beEqualTo(200)
      actualDbCount must beEqualTo(expectedDbCount)
    }

    e2eScenario.unsafeRunSync()
  }

  private def buildTP2CollectorRequest(payload: Json): Request[IO] = {
    val uri = Uri.unsafeFromString(s"${System.getenv(collectorHostEnv)}/com.snowplowanalytics.snowplow/tp2")
    Request[IO](Method.POST, uri).withEntity(payload)
  }

  private def executeHttpRequest(request: Request[IO]): IO[Response[IO]] =
    createClient.use(client => client.run(request).use(resp => IO.pure(resp)))

  private def waitUntilAllDataReachDB(appId: String, expectedDbCount: Int): IO[Long] =
    retryingOnFailures[Long](
      policy        = RetryPolicies.capDelay[IO](timeout, RetryPolicies.constantDelay[IO](10.seconds)),
      wasSuccessful = actualDbCount => actualDbCount == expectedDbCount,
      onFailure     = (_, retryDetails) => IO.delay(println(renderRetryDetails(retryDetails)))
    )(countDataInDB(appId))

  private def countDataInDB(appId: String) =
    countEventsWithAppIdQuery(appId).query[Long].unique.transact(transactor)

  private def createClient: Resource[IO, Client[IO]] =
    BlazeClientBuilder[IO](ExecutionContext.global).resource

  private def anyEnvironmentVariableMissing(): Boolean =
    (collectorHostEnv :: storageEnvironmentVariables).exists(varName => System.getenv(varName) == null)

  private def renderRetryDetails(retryDetails: RetryDetails): String =
    retryDetails match {
      case RetryDetails.GivingUp(totalRetries, totalDelay) =>
        s"Giving up, number of retries - $totalRetries, totalDelay - $totalDelay"
      case RetryDetails.WillDelayAndRetry(_, retriesSoFar, cumulativeDelay) =>
        s"Retrying database query, retries so far - $retriesSoFar, cumulative delay - $cumulativeDelay"
    }
}
