package com.snowplowanalytics.snowplow.collectors.scalastream

import java.util.UUID

import scala.collection.JavaConverters._

import cats.effect.Sync
import cats.implicits._

import org.http4s.{Request, RequestCookie, Response}
import org.http4s.Status._

import org.typelevel.ci._

import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload

import com.snowplowanalytics.snowplow.collectors.scalastream.model._

trait Service[F[_]] {
  def cookie(
    queryString: Option[String],
    body: F[Option[String]],
    path: String,
    cookie: Option[RequestCookie],
    userAgent: Option[String],
    refererUri: Option[String],
    hostname: F[Option[String]],
    ip: Option[String],
    request: Request[F],
    pixelExpected: Boolean,
    doNotTrack: Boolean,
    contentType: Option[String] = None,
    spAnonymous: Option[String] = None
  ): F[Response[F]]
  def determinePath(vendor: String, version: String): String
}

class CollectorService[F[_]: Sync](
  config: CollectorConfig,
  sinks: CollectorSinks[F],
  appName: String,
  appVersion: String
) extends Service[F] {

  // TODO: Add sink type as well
  private val collector = s"$appName-$appVersion"

  private val splitBatch: SplitBatch = SplitBatch(appName, appVersion)

  def cookie(
    queryString: Option[String],
    body: F[Option[String]],
    path: String,
    cookie: Option[RequestCookie],
    userAgent: Option[String],
    refererUri: Option[String],
    hostname: F[Option[String]],
    ip: Option[String],
    request: Request[F],
    pixelExpected: Boolean,
    doNotTrack: Boolean,
    contentType: Option[String] = None,
    spAnonymous: Option[String] = None
  ): F[Response[F]] =
    for {
      body     <- body
      hostname <- hostname
      // TODO: Get ipAsPartitionKey from config
      (ipAddress, partitionKey) = ipAndPartitionKey(ip, ipAsPartitionKey = false)
      // TODO: nuid should be set properly
      nuid = UUID.randomUUID().toString
      event = buildEvent(
        queryString,
        body,
        path,
        userAgent,
        refererUri,
        hostname,
        ipAddress,
        nuid,
        contentType,
        headers(request, spAnonymous)
      )
      _ <- sinkEvent(event, partitionKey)
    } yield buildHttpResponse

  def determinePath(vendor: String, version: String): String = {
    val original = s"/$vendor/$version"
    config.paths.getOrElse(original, original)
  }

  /** Builds a raw event from an Http request. */
  def buildEvent(
    queryString: Option[String],
    body: Option[String],
    path: String,
    userAgent: Option[String],
    refererUri: Option[String],
    hostname: Option[String],
    ipAddress: String,
    networkUserId: String,
    contentType: Option[String],
    headers: List[String]
  ): CollectorPayload = {
    val e = new CollectorPayload(
      "iglu:com.snowplowanalytics.snowplow/CollectorPayload/thrift/1-0-0",
      ipAddress,
      System.currentTimeMillis,
      "UTF-8",
      collector
    )
    queryString.foreach(e.querystring = _)
    body.foreach(e.body               = _)
    e.path = path
    userAgent.foreach(e.userAgent   = _)
    refererUri.foreach(e.refererUri = _)
    hostname.foreach(e.hostname     = _)
    e.networkUserId = networkUserId
    e.headers       = (headers ++ contentType).asJava
    contentType.foreach(e.contentType = _)
    e
  }

  // TODO: Handle necessary cases to build http response in here
  def buildHttpResponse: Response[F] = Response(status = Ok)

  // TODO: Since Remote-Address and Raw-Request-URI is akka-specific headers,
  // they aren't included in here. It might be good to search for counterparts in Http4s.
  /** If the SP-Anonymous header is not present, retrieves all headers
    * from the request.
    * If the SP-Anonymous header is present, additionally filters out the
    * X-Forwarded-For, X-Real-IP and Cookie headers as well.
    */
  def headers(request: Request[F], spAnonymous: Option[String]): List[String] =
    request.headers.headers.flatMap { h =>
      h.name match {
        case ci"X-Forwarded-For" | ci"X-Real-Ip" | ci"Cookie" if spAnonymous.isDefined => None
        case _ => Some(h.toString())
      }
    }

  /** Produces the event to the configured sink. */
  def sinkEvent(
    event: CollectorPayload,
    partitionKey: String
  ): F[Unit] =
    for {
      // Split events into Good and Bad
      eventSplit <- Sync[F].delay(splitBatch.splitAndSerializePayload(event, sinks.good.maxBytes))
      // Send events to respective sinks
      _ <- sinks.good.storeRawEvents(eventSplit.good, partitionKey)
      _ <- sinks.bad.storeRawEvents(eventSplit.bad, partitionKey)
    } yield ()

  /**
    * Gets the IP from a RemoteAddress. If ipAsPartitionKey is false, a UUID will be generated.
    *
    * @param remoteAddress    Address extracted from an HTTP request
    * @param ipAsPartitionKey Whether to use the ip as a partition key or a random UUID
    * @return a tuple of ip (unknown if it couldn't be extracted) and partition key
    */
  def ipAndPartitionKey(
    ipAddress: Option[String],
    ipAsPartitionKey: Boolean
  ): (String, String) =
    ipAddress match {
      case None     => ("unknown", UUID.randomUUID.toString)
      case Some(ip) => (ip, if (ipAsPartitionKey) ip else UUID.randomUUID.toString)
    }
}
