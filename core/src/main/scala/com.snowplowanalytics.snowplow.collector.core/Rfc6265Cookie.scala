/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This software is made available by Snowplow Analytics, Ltd.,
  * under the terms of the Snowplow Limited Use License Agreement, Version 1.0
  * located at https://docs.snowplow.io/limited-use-license-1.0
  * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
  * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
  */
package com.snowplowanalytics.snowplow.collector.core

object Rfc6265Cookie {

  // See https://www.ietf.org/rfc/rfc6265.txt
  private val allowedChars = Set(0x21.toChar) ++
    Set(0x23.toChar to 0x2b.toChar: _*) ++
    Set(0x2d.toChar to 0x3a.toChar: _*) ++
    Set(0x3c.toChar to 0x5b.toChar: _*) ++
    Set(0x5d.toChar to 0x7e.toChar: _*)

  // Remove all the sub-parts (between two ';') that contain unauthorized characters
  def parse(rawCookie: String): Option[String] =
    rawCookie.replaceAll(" ", "").split(";").filter(_.forall(allowedChars.contains)).mkString(";") match {
      case s if s.nonEmpty => Some(s)
      case _               => None
    }
}
