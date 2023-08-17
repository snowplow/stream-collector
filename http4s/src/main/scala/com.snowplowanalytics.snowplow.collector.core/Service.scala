package com.snowplowanalytics.snowplow.collector.core

import java.util.UUID

import org.apache.commons.codec.binary.Base64

import scala.concurrent.duration._
import scala.collection.JavaConverters._

import cats.effect.{Clock, Sync}
import cats.implicits._

import fs2.Stream

import org.http4s._
import org.http4s.headers._
import org.http4s.implicits._
import org.http4s.Status._

import org.typelevel.ci._

import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload

import com.snowplowanalytics.snowplow.collector.core.model._

trait IService[F[_]] {
  def preflightResponse(req: Request[F]): F[Response[F]]
  def cookie(
    body: F[Option[String]],
    path: String,
    request: Request[F],
    pixelExpected: Boolean,
    doNotTrack: Boolean,
    contentType: Option[String] = None
  ): F[Response[F]]
  def determinePath(vendor: String, version: String): String
}

object Service {
  // Contains an invisible pixel to return for `/i` requests.
  val pixel = Base64.decodeBase64("R0lGODlhAQABAPAAAP///wAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw==")

  val spAnonymousNuid = "00000000-0000-0000-0000-000000000000"
}

class Service[F[_]: Sync](
  config: Config[Any],
  sinks: Sinks[F],
  appInfo: AppInfo
) extends IService[F] {

  val pixelStream = Stream.iterable[F, Byte](Service.pixel)

  private val collector = s"${appInfo.name}:${appInfo.version}"

  private val splitBatch: SplitBatch = SplitBatch(appInfo)

  override def cookie(
    body: F[Option[String]],
    path: String,
    request: Request[F],
    pixelExpected: Boolean,
    doNotTrack: Boolean,
    contentType: Option[String] = None
  ): F[Response[F]] =
    for {
      body <- body
      redirect                  = path.startsWith("/r/")
      hostname                  = extractHostname(request)
      userAgent                 = extractHeader(request, "User-Agent")
      refererUri                = extractHeader(request, "Referer")
      spAnonymous               = extractHeader(request, "SP-Anonymous")
      ip                        = extractIp(request, spAnonymous)
      queryString               = Some(request.queryString)
      cookie                    = extractCookie(request)
      nuidOpt                   = networkUserId(request, cookie, spAnonymous)
      nuid                      = nuidOpt.getOrElse(UUID.randomUUID().toString)
      (ipAddress, partitionKey) = ipAndPartitionKey(ip, config.streams.useIpAddressAsPartitionKey)
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
        cookieConfig  = config.cookie,
        networkUserId = nuid,
        doNotTrack    = doNotTrack,
        spAnonymous   = spAnonymous,
        now           = now
      )
      headerList = List(
        setCookie.map(_.toRaw1),
        cacheControl(pixelExpected).map(_.toRaw1),
        accessControlAllowOriginHeader(request).some,
        `Access-Control-Allow-Credentials`().toRaw1.some
      ).flatten
      responseHeaders = Headers(headerList)
      _ <- sinkEvent(event, partitionKey)
      resp = buildHttpResponse(
        queryParams   = request.uri.query.params,
        headers       = responseHeaders,
        redirect      = redirect,
        pixelExpected = pixelExpected
      )
    } yield resp

  override def determinePath(vendor: String, version: String): String = {
    val original = s"/$vendor/$version"
    config.paths.getOrElse(original, original)
  }

  override def preflightResponse(req: Request[F]): F[Response[F]] = Sync[F].pure {
    Response[F](
      headers = Headers(
        accessControlAllowOriginHeader(req),
        `Access-Control-Allow-Credentials`(),
        `Access-Control-Allow-Headers`(ci"Content-Type", ci"SP-Anonymous"),
        `Access-Control-Max-Age`.Cache(config.cors.accessControlMaxAge.toSeconds).asInstanceOf[`Access-Control-Max-Age`]
      )
    )
  }

  def extractHeader(req: Request[F], headerName: String): Option[String] =
    req.headers.get(CIString(headerName)).map(_.head.value)

  def extractCookie(req: Request[F]): Option[RequestCookie] =
    req.cookies.find(_.name == config.cookie.name)

  def extractHostname(req: Request[F]): Option[String] =
    req.uri.authority.map(_.host.renderString) // Hostname is extracted like this in Akka-Http as well

  def extractIp(req: Request[F], spAnonymous: Option[String]): Option[String] =
    spAnonymous match {
      case None    => req.from.map(_.toUriString)
      case Some(_) => None
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

  def buildHttpResponse(
    queryParams: Map[String, String],
    headers: Headers,
    redirect: Boolean,
    pixelExpected: Boolean
  ): Response[F] =
    if (redirect)
      buildRedirectHttpResponse(queryParams, headers)
    else
      buildUsualHttpResponse(pixelExpected, headers)

  /** Builds the appropriate http response when not dealing with click redirects. */
  def buildUsualHttpResponse(pixelExpected: Boolean, headers: Headers): Response[F] =
    pixelExpected match {
      case true =>
        Response[F](
          headers = headers.put(`Content-Type`(MediaType.image.gif)),
          body    = pixelStream
        )
      // See https://github.com/snowplow/snowplow-javascript-tracker/issues/482
      case false =>
        Response[F](
          status  = Ok,
          headers = headers,
          body    = Stream.emit("ok").through(fs2.text.utf8.encode)
        )
    }

  /** Builds the appropriate http response when dealing with click redirects. */
  def buildRedirectHttpResponse(queryParams: Map[String, String], headers: Headers): Response[F] = {
    val targetUri = for {
      target <- queryParams.get("u")
      uri    <- Uri.fromString(target).toOption
      if redirectTargetAllowed(uri)
    } yield uri

    targetUri match {
      case Some(t) =>
        Response[F](
          status  = Found,
          headers = headers.put(Location(t))
        )
      case _ =>
        Response[F](
          status  = BadRequest,
          headers = headers
        )
    }
  }

  private def redirectTargetAllowed(target: Uri): Boolean =
    if (config.redirectDomains.isEmpty) true
    else config.redirectDomains.contains(target.host.map(_.renderString).getOrElse(""))

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

  /** If the pixel is requested, this attaches cache control headers to the response to prevent any caching. */
  def cacheControl(pixelExpected: Boolean): Option[`Cache-Control`] =
    if (pixelExpected)
      Some(`Cache-Control`(CacheDirective.`no-cache`(), CacheDirective.`no-store`, CacheDirective.`must-revalidate`))
    else None

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
    cookieConfig: Config.Cookie,
    networkUserId: String,
    doNotTrack: Boolean,
    spAnonymous: Option[String],
    now: FiniteDuration
  ): Option[`Set-Cookie`] =
    (doNotTrack, cookieConfig.enabled, spAnonymous) match {
      case (true, _, _)    => None
      case (_, false, _)   => None
      case (_, _, Some(_)) => None
      case _ =>
        val responseCookie = ResponseCookie(
          name     = cookieConfig.name,
          content  = networkUserId,
          expires  = Some(HttpDate.unsafeFromEpochSecond((now + cookieConfig.expiration).toSeconds)),
          domain   = cookieDomain(headers, cookieConfig.domains, cookieConfig.fallbackDomain),
          path     = Some("/"),
          sameSite = cookieConfig.sameSite,
          secure   = cookieConfig.secure,
          httpOnly = cookieConfig.httpOnly
        )
        Some(`Set-Cookie`(responseCookie))
    }

  /**
    * Creates an Access-Control-Allow-Origin header which specifically allows the domain which made
    * the request
    *
    * @param request Incoming request
    * @return Header allowing only the domain which made the request or everything
    */
  def accessControlAllowOriginHeader(request: Request[F]): Header.Raw =
    Header.Raw(
      ci"Access-Control-Allow-Origin",
      extractHostsFromOrigin(request.headers).headOption.map(_.renderString).getOrElse("*")
    )

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
        val originDomains = extractHostsFromOrigin(headers).map(_.host.value)
        domains.find(domain => originDomains.exists(validMatch(_, domain)))
    }).orElse(fallbackDomain)

  /** Extracts the host names from a list of values in the request's Origin header. */
  def extractHostsFromOrigin(headers: Headers): List[Origin.Host] =
    (for {
      // We can't use 'headers.get[Origin]' function in here because of the bug
      // reported here: https://github.com/http4s/http4s/issues/7236
      // To circumvent the bug, we split the the Origin header value with blank char
      // and parse items individually.
      originSplit <- headers.get(ci"Origin").map(_.head.value.split(' '))
      parsed = originSplit.map(Origin.parse(_).toOption).toList.flatten
      hosts = parsed.flatMap {
        case Origin.Null            => List.empty
        case Origin.HostList(hosts) => hosts.toList
      }
    } yield hosts).getOrElse(List.empty)

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

  /**
    * Gets the network user id from the query string or the request cookie.
    *
    * @param request       Http request made
    * @param requestCookie cookie associated to the Http request
    * @return a network user id
    */
  def networkUserId(
    request: Request[F],
    requestCookie: Option[RequestCookie],
    spAnonymous: Option[String]
  ): Option[String] =
    spAnonymous match {
      case Some(_) => Some(Service.spAnonymousNuid)
      case None    => request.uri.query.params.get("nuid").orElse(requestCookie.map(_.content))
    }
}
