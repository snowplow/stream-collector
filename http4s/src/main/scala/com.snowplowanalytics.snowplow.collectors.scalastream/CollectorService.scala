package com.snowplowanalytics.snowplow.collectors.scalastream

import java.util.UUID

import scala.concurrent.duration._
import scala.collection.JavaConverters._

import cats.effect.{Clock, Sync}
import cats.implicits._

import org.http4s._
import org.http4s.headers._
import org.http4s.implicits._
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
      now <- Clock[F].realTime
      setCookie = cookieHeader(
        headers       = request.headers,
        cookieConfig  = config.cookieConfig,
        networkUserId = nuid,
        doNotTrack    = doNotTrack,
        spAnonymous   = spAnonymous,
        now           = now
      )
      responseHeaders = Headers(setCookie.toList.map(_.toRaw1))
      _ <- sinkEvent(event, partitionKey)
    } yield buildHttpResponse(responseHeaders)

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
  def buildHttpResponse(headers: Headers): Response[F] =
    Response(status = Ok, headers = headers)

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
    * Builds a cookie header with the network user id as value.
    *
    * @param cookieConfig  cookie configuration extracted from the collector configuration
    * @param networkUserId value of the cookie
    * @param doNotTrack    whether do not track is enabled or not
    * @return the build cookie wrapped in a header
    */
  def cookieHeader(
    headers: Headers,
    cookieConfig: Option[CookieConfig],
    networkUserId: String,
    doNotTrack: Boolean,
    spAnonymous: Option[String],
    now: FiniteDuration
  ): Option[`Set-Cookie`] =
    if (doNotTrack) {
      None
    } else {
      spAnonymous match {
        case Some(_) => None
        case None =>
          cookieConfig.map { config =>
            val responseCookie = ResponseCookie(
              name     = config.name,
              content  = networkUserId,
              expires  = Some(HttpDate.unsafeFromEpochSecond((now + config.expiration).toSeconds)),
              domain   = cookieDomain(headers, config.domains, config.fallbackDomain),
              path     = Some("/"),
              sameSite = config.sameSite,
              secure   = config.secure,
              httpOnly = config.httpOnly
            )
            `Set-Cookie`(responseCookie)
          }
      }
    }

  /**
    * Determines the cookie domain to be used by inspecting the Origin header of the request
    * and trying to find a match in the list of domains specified in the config file.
    *
    * @param headers        The headers from the http request.
    * @param domains        The list of cookie domains from the configuration.
    * @param fallbackDomain The fallback domain from the configuration.
    * @return The domain to be sent back in the response, unless no cookie domains are configured.
    *         The Origin header may include multiple domains. The first matching domain is returned.
    *         If no match is found, the fallback domain is used if configured. Otherwise, the cookie domain is not set.
    */
  def cookieDomain(
    headers: Headers,
    domains: List[String],
    fallbackDomain: Option[String]
  ): Option[String] =
    (domains match {
      case Nil => None
      case _ =>
        val originHosts = extractHosts(headers)
        domains.find(domain => originHosts.exists(validMatch(_, domain)))
    }).orElse(fallbackDomain)

  /** Extracts the host names from a list of values in the request's Origin header. */
  def extractHosts(headers: Headers): List[String] =
    (for {
      // We can't use 'headers.get[Origin]' function in here because of the bug
      // reported here: https://github.com/http4s/http4s/issues/7236
      // To circumvent the bug, we split the the Origin header value with blank char
      // and parse items individually.
      originSplit <- headers.get(ci"Origin").map(_.head.value.split(' '))
      parsed = originSplit.map(Origin.parse(_).toOption).toList.flatten
      hosts  = parsed.flatMap(extractHostFromOrigin)
    } yield hosts).getOrElse(List.empty)

  private def extractHostFromOrigin(originHeader: Origin): List[String] =
    originHeader match {
      case Origin.Null            => List.empty
      case Origin.HostList(hosts) => hosts.map(_.host.value).toList
    }

  /**
    * Ensures a match is valid.
    * We only want matches where:
    * a.) the Origin host is exactly equal to the cookie domain from the config
    * b.) the Origin host is a subdomain of the cookie domain from the config.
    * But we want to avoid cases where the cookie domain from the config is randomly
    * a substring of the Origin host, without any connection between them.
    */
  def validMatch(host: String, domain: String): Boolean =
    host == domain || host.endsWith("." + domain)

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
