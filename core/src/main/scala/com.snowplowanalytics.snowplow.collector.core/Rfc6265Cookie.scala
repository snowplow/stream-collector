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

object Rfc6265Cookie {

  private val Dquote = '"'

  /**
    * The `cookie-octet` of RFC 6265 section 4.1.1: US-ASCII characters excluding CTLs,
    * whitespace, DQUOTE, comma, semicolon and backslash.
    * See https://www.ietf.org/rfc/rfc6265.txt
    */
  private def isCookieOctet(c: Char): Boolean =
    c == 0x21                  || (c >= 0x23 && c <= 0x2b) || (c >= 0x2d && c <= 0x3a) ||
      (c >= 0x3c && c <= 0x5b) || (c >= 0x5d && c <= 0x7e)

  // Remove all the sub-parts (between two ';') that contain unauthorized characters
  def parse(rawCookie: String): Option[String] =
    rawCookie.filterNot(_ == ' ').split(";").flatMap(parsePair).mkString(";") match {
      case s if s.nonEmpty => Some(s)
      case _               => None
    }

  /**
    * Validate one sub-part of the Cookie header, unwrapping a DQUOTE-wrapped value.
    *
    * `cookie-value` is `*cookie-octet / ( DQUOTE *cookie-octet DQUOTE )`, so a value may
    * legally be wrapped in quotes. Only the inner value is forwarded: section 4.2.2 leaves
    * what a server makes of a received cookie to the server, so the wrapping is treated as
    * an encoding convention rather than payload.
    */
  private def parsePair(pair: String): Option[String] =
    pair.indexOf('=') match {
      case -1 => Some(pair).filter(isCookieOctets)
      case i =>
        val name  = pair.substring(0, i)
        val value = unwrap(pair.substring(i + 1))
        if (isCookieOctets(name) && isCookieOctets(value)) Some(s"$name=$value") else None
    }

  /**
    * Strip one leading and one trailing DQUOTE, if both are there.
    *
    * `cookie-octet` excludes DQUOTE, so a valid value can only hold one in each of those
    * two positions. Stripping them can therefore never mistake payload for a delimiter,
    * and a value quoted anywhere else stays invalid.
    */
  private def unwrap(value: String): String =
    if (value.length >= 2 && value.head == Dquote && value.last == Dquote)
      value.substring(1, value.length - 1)
    else
      value

  private def isCookieOctets(s: String): Boolean = s.forall(isCookieOctet)
}
