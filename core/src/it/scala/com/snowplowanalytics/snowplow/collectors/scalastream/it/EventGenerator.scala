/*
 * Copyright (c) 2022-2023 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, and
 * you may not use this file except in compliance with the Apache License
 * Version 2.0.  You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Apache License Version 2.0 is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream.it

import scala.concurrent.ExecutionContext

import cats.implicits._

import cats.effect.{ContextShift, IO, Resource}

import org.http4s.{Request, Method, Uri}
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder

object EventGenerator {

  private val executionContext = ExecutionContext.global
  implicit val ioContextShift: ContextShift[IO] = IO.contextShift(executionContext)

  def sendRequests(
    collectorHost: String,
    collectorPort: Int,
    nbGood: Int,
    nbBad: Int,
    maxBytes: Int
  ): IO[Unit] =
    mkHttpClient.use { client =>
      val uri = Uri.unsafeFromString(s"http://$collectorHost:$collectorPort/com.snowplowanalytics.snowplow/tp2")
      val requests = generateEvents(uri, nbGood, valid = true, maxBytes) ++ generateEvents(uri, nbBad, valid = false, maxBytes)
      requests.traverse(client.status)
        .flatMap { responses =>
          responses.collect { case resp if resp.code != 200 => resp.reason } match {
            case Nil => IO.unit
            case errors => IO.raiseError(new RuntimeException(s"${errors.size} requests were not successful. Example error: ${errors.head}"))
          }
        }
    }

  private def generateEvents(uri: Uri, nbEvents: Int, valid: Boolean, maxBytes: Int): List[Request[IO]] = {
    val body = if (valid) "foo" else "a" * (maxBytes + 1)
    val one = Request[IO](Method.POST, uri).withEntity(body)
    List.fill(nbEvents)(one)
  }

  private def mkHttpClient: Resource[IO, Client[IO]] =
    BlazeClientBuilder[IO](executionContext).resource
}