package com.snowplowanalytics.snowplow.collectors.scalastream

import cats.effect.IO
import org.http4s._
import org.http4s.blaze.client.BlazeClientBuilder
import org.typelevel.ci._
import io.circe.parser

object TelemetryUtils {

  // Metadata service response will be used to get Azure subscription id
  // More information about the service can be found here:
  // https://learn.microsoft.com/en-us/azure/virtual-machines/instance-metadata-service
  val azureMetadataServiceUrl = "http://169.254.169.254/metadata/instance?api-version=2021-02-01"

  def getAzureSubscriptionId: IO[Option[String]] = {
    val response = for {
      client <- BlazeClientBuilder[IO].resource
      request = Request[IO](
        method  = Method.GET,
        uri     = Uri.unsafeFromString(azureMetadataServiceUrl),
        headers = Headers(Header.Raw(ci"Metadata", "true"))
      )
      response <- client.run(request)
    } yield response
    response.use(_.bodyText.compile.string.map(extractId)).handleError(_ => None)
  }

  private def extractId(metadata: String): Option[String] =
    for {
      json <- parser.parse(metadata).toOption
      id   <- json.hcursor.downField("compute").downField("subscriptionId").as[String].toOption
    } yield id
}
