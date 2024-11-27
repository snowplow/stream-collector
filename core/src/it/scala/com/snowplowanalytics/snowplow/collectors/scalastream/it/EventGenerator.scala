/**
 * Copyright (c) 2013-present Snowplow Analytics Ltd.
 * All rights reserved.
 *
 * This software is made available by Snowplow Analytics, Ltd.,
 * under the terms of the Snowplow Limited Use License Agreement, Version 1.0
 * located at https://docs.snowplow.io/limited-use-license-1.1
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
 * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream.it

import cats.effect.IO

import org.http4s.{Method, Request, Uri}

object EventGenerator {

  def sendEvents(
    collectorHost: String,
    collectorPort: Int,
    nbGood: Int,
    nbBad: Int,
    maxBytes: Int
  ): IO[Unit] = {
    val requests = generateEvents(collectorHost, collectorPort, nbGood, nbBad, maxBytes)
    Http.statuses(requests)
      .flatMap { responses =>
        responses.collect { case resp if resp.code != 200 => resp.reason } match {
          case Nil => IO.unit
          case errors => IO.raiseError(new RuntimeException(s"${errors.size} requests were not successful. Example error: ${errors.head}"))
        }
      }
  }

  def generateEvents(
    collectorHost: String,
    collectorPort: Int,
    nbGood: Int,
    nbBad: Int,
    maxBytes: Int
  ): List[Request[IO]] = {
    val good = List.fill(nbGood)(mkTp2Event(collectorHost, collectorPort, valid = true, maxBytes))
    val bad = List.fill(nbBad)(mkTp2Event(collectorHost, collectorPort, valid = false, maxBytes))
    good ++ bad
  }

  def mkTp2Event(
    collectorHost: String,
    collectorPort: Int,
    valid: Boolean = true,
    maxBytes: Int = 100
  ): Request[IO] = {
    val uri = Uri.unsafeFromString(s"http://$collectorHost:$collectorPort/com.snowplowanalytics.snowplow/tp2")
    val body = if (valid) "foo" else "a" * (maxBytes + 1)
    Request[IO](Method.POST, uri).withEntity(body)
  }
}
