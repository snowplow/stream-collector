/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This software is made available by Snowplow Analytics, Ltd.,
  * under the terms of the Snowplow Limited Use License Agreement, Version 1.1
  * located at https://docs.snowplow.io/limited-use-license-1.1
  * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
  * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
  */
package com.snowplowanalytics.snowplow.collector.core

import java.util.UUID

import org.apache.commons.codec.binary.Base64

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

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
    contentType: Option[String] = None
  ): F[Response[F]]
  def determinePath(vendor: String, version: String): String
  def sinksHealthy: F[Boolean]
  def rootResponse: F[Response[F]]
  def crossdomainResponse: F[Response[F]]
}

object Service {
  // Contains an invisible pixel to return for `/i` requests.
  val pixel           = Base64.decodeBase64("R0lGODlhAQABAPAAAP///wAAACH5BAUAAAAALAAAAAABAAEAAAICRAEAOw==")
  val spAnonymousNuid = "00000000-0000-0000-0000-000000000000"
}

class Service[F[_]: Sync](
  config: Config[Any],
  sinks: Sinks[F],
  appInfo: AppInfo
) extends IService[F] {

  val pixelStream = Stream.iterable[F, Byte](Service.pixel)

  private val collector =
    s"""${appInfo.shortName}-${appInfo.version}-${sinks.good.getClass.getSimpleName.toLowerCase}"""

  private val splitBatch: SplitBatch = SplitBatch(appInfo)

  override def cookie(
    body: F[Option[String]],
    path: String,
    request: Request[F],
    pixelExpected: Boolean,
    contentType: Option[String] = None
  ): F[Response[F]] =
    for {
      body <- body
      redirect        = path.startsWith("/r/")
      hostname        = extractHostname(request)
      userAgent       = extractHeader(request, "User-Agent")
      refererUri      = extractHeader(request, "Referer")
      spAnonymous     = extractHeader(request, "SP-Anonymous").isDefined
      ip              = extractIp(request, spAnonymous)
      queryString     = Some(request.queryString)
      cookie          = extractCookie(request)
      doNotTrack      = checkDoNotTrackCookie(request)
      alreadyBouncing = config.cookieBounce.enabled && request.queryString.contains(config.cookieBounce.name)
      nuidOpt         = networkUserId(request, cookie, spAnonymous)
      nuid = nuidOpt.getOrElse {
        if (alreadyBouncing) config.cookieBounce.fallbackNetworkUserId
        else UUID.randomUUID().toString
      }
      shouldBounce              = config.cookieBounce.enabled && nuidOpt.isEmpty && !alreadyBouncing && pixelExpected && !redirect
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
      createCookieHeader = { c: Config.Cookie =>
        cookieHeader(
          headers         = request.headers,
          cookieConfig    = c,
          networkUserId   = nuid,
          doNotTrack      = doNotTrack,
          spAnonymous     = spAnonymous,
          now             = now,
          cookieInRequest = cookie.isDefined
        )
      }
      setCookieHeader       = createCookieHeader(config.cookie)
      clientSetCookieHeader = config.cookie.clientCookie.flatMap(createCookieHeader)
      headerList = List(
        setCookieHeader.map(_.toRaw1),
        clientSetCookieHeader.map(_.toRaw1),
        cacheControl(pixelExpected).map(_.toRaw1),
        accessControlAllowOriginHeader(request).some,
        `Access-Control-Allow-Credentials`().toRaw1.some
      ).flatten
      responseHeaders = Headers(headerList ++ bounceLocationHeaders(config.cookieBounce, shouldBounce, request))
      _ <- if (!doNotTrack && !shouldBounce) sinkEvent(event, partitionKey) else Sync[F].unit
      resp = buildHttpResponse(
        queryParams   = request.uri.query.params,
        headers       = responseHeaders,
        redirect      = redirect,
        pixelExpected = pixelExpected,
        shouldBounce  = shouldBounce
      )
    } yield resp

  override def sinksHealthy: F[Boolean] = (sinks.good.isHealthy, sinks.bad.isHealthy).mapN(_ && _)

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

  override def rootResponse: F[Response[F]] = Sync[F].fromEither {
    for {
      status <- Status.fromInt(config.rootResponse.statusCode)
      body = Stream.emit(config.rootResponse.body).through(fs2.text.utf8.encode)
      headers = Headers(
        config.rootResponse.headers.toList.map { case (name, value) => Header.Raw(CIString(name), value) }
      )
    } yield Response[F](
      status  = status,
      body    = body,
      headers = headers
    )
  }

  def crossdomainResponse: F[Response[F]] = Sync[F].pure {
    val policy =
      config
        .crossDomain
        .domains
        .map(d => s"""<allow-access-from domain="${d}" secure="${config.crossDomain.secure}" />""")
        .mkString("\n")

    val xml = s"""<?xml version="1.0"?>
                 |<cross-domain-policy>
                 |${policy}
                 |</cross-domain-policy>""".stripMargin

    Response[F](
      status  = Ok,
      body    = Stream.emit(xml).through(fs2.text.utf8.encode),
      headers = Headers(`Content-Type`(MediaType.text.xml))
    )
  }

  def extractHeader(req: Request[F], headerName: String): Option[String] =
    req.headers.get(CIString(headerName)).map(_.head.value)

  def extractCookie(req: Request[F]): Option[RequestCookie] =
    req.cookies.find(_.name == config.cookie.name)

  def checkDoNotTrackCookie(req: Request[F]): Boolean =
    config.doNotTrackCookie.enabled && req
      .cookies
      .find(_.name == config.doNotTrackCookie.name)
      .exists(cookie => config.doNotTrackCookie.value.r.pattern.matcher(cookie.content).matches())

  def extractHostname(req: Request[F]): Option[String] =
    req.uri.authority.map(_.host.renderString) // Hostname is extracted like this in Akka-Http as well

  def extractIp(req: Request[F], spAnonymous: Boolean): Option[String] =
    if (spAnonymous) None
    else req.from.map(_.toUriString)

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
    pixelExpected: Boolean,
    shouldBounce: Boolean
  ): Response[F] =
    if (redirect)
      buildRedirectHttpResponse(queryParams, headers)
    else
      buildUsualHttpResponse(pixelExpected, shouldBounce, headers)

  /** Builds the appropriate http response when not dealing with click redirects. */
  def buildUsualHttpResponse(pixelExpected: Boolean, shouldBounce: Boolean, headers: Headers): Response[F] =
    (pixelExpected, shouldBounce) match {
      case (true, true) => Response[F](status = Found, headers = headers)
      case (true, false) =>
        Response[F](
          headers = headers.put(`Content-Type`(MediaType.image.gif)),
          body    = pixelStream
        )
      // See https://github.com/snowplow/snowplow-javascript-tracker/issues/482
      case _ =>
        Response[F](
          status  = Ok,
          headers = headers.put(`Content-Type`(MediaType.text.plain)),
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
  def headers(request: Request[F], spAnonymous: Boolean): List[String] =
    request.headers.headers.flatMap { h =>
      h.name match {
        case ci"X-Forwarded-For" | ci"X-Real-Ip" | ci"Cookie" if spAnonymous => None
        // FIXME: This is a temporary backport of old akka behaviour we will remove by
        //        adapting enrich to support a CIString header names as per RFC7230#Section-3.2
        case ci"Cookie" => Rfc6265Cookie.parse(h.value).map(c => s"Cookie: $c")
        case _          => Some(h.toString())
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
      eventSplit <- Sync[F].delay(
        splitBatch.splitAndSerializePayload(event, sinks.good.maxBytes, config.networking.maxPayloadSize)
      )
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
    spAnonymous: Boolean,
    now: FiniteDuration,
    cookieInRequest: Boolean
  ): Option[`Set-Cookie`] =
    (doNotTrack, cookieConfig.enabled, spAnonymous) match {
      case (true, _, _)  => None
      case (_, false, _) => None
      case (_, _, true) =>
        if (cookieInRequest) {
          val responseCookie = ResponseCookie(
            name     = cookieConfig.name,
            content  = "",
            expires  = HttpDate.fromEpochSecond((now - cookieConfig.expiration).toSeconds).toOption,
            domain   = cookieDomain(headers, cookieConfig.domains, cookieConfig.fallbackDomain),
            path     = Some("/"),
            sameSite = cookieConfig.sameSite,
            secure   = cookieConfig.secure,
            httpOnly = cookieConfig.httpOnly
          )
          Some(`Set-Cookie`(responseCookie))
        } else None
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
    spAnonymous: Boolean
  ): Option[String] =
    if (spAnonymous) Some(Service.spAnonymousNuid)
    else request.uri.query.params.get("nuid").orElse(requestCookie.map(_.content))

  /**
    * Builds a location header redirecting to itself to check if third-party cookies are blocked.
    *
    * @param request
    * @param shouldBounce
    * @return http optional location header
    */
  def bounceLocationHeaders(cfg: Config.CookieBounce, shouldBounce: Boolean, request: Request[F]): Option[Header.Raw] =
    if (shouldBounce) Some {
      val forwardedScheme = for {
        headerName  <- cfg.forwardedProtocolHeader.map(CIString(_))
        headerValue <- request.headers.get(headerName).flatMap(_.map(_.value).toList.headOption)
        maybeScheme <- if (Set("http", "https").contains(headerValue)) Some(headerValue) else None
        _           <- request.uri.authority.map(_.host)
        scheme      <- Uri.Scheme.fromString(maybeScheme).toOption
      } yield scheme
      val redirectUri =
        request.uri.withQueryParam(cfg.name, "true").copy(scheme = forwardedScheme)

      `Location`(redirectUri).toRaw1
    } else None

}
