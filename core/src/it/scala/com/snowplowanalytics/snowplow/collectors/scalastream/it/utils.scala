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
package com.snowplowanalytics.snowplow.collectors.scalastream.it

import scala.concurrent.duration._

import org.apache.thrift.TDeserializer

import org.slf4j.LoggerFactory

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.output.Slf4jLogConsumer

import io.circe.{Json, parser}

import cats.implicits._

import cats.effect.IO

import retry.syntax.all._
import retry.RetryPolicies

import com.snowplowanalytics.snowplow.badrows.BadRow

import com.snowplowanalytics.iglu.core.SelfDescribingData
import com.snowplowanalytics.iglu.core.circe.implicits._

import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload

object utils {

  def parseCollectorPayload(bytes: Array[Byte]): CollectorPayload = {
    val deserializer = new TDeserializer()
    val target = new CollectorPayload()
    deserializer.deserialize(target, bytes)
    target
  }

  def parseBadRow(bytes: Array[Byte]): BadRow = {
    val str = new String(bytes)
    val parsed = for {
      json <- parser.parse(str).leftMap(_.message)
      sdj <- SelfDescribingData.parse(json).leftMap(_.message("Can't decode JSON as SDJ"))
      br <- sdj.data.as[BadRow].leftMap(_.getMessage())
    } yield br
    parsed match {
      case Right(br) => br
      case Left(err) => throw new RuntimeException(s"Can't parse bad row. Error: $err")
    }
  }

  def printBadRows(testName: String, badRows: List[BadRow]): IO[Unit] = {
    log(testName, "Bad rows:") *>
      badRows.traverse_(br => log(testName, br.compact))
  }

  def log(testName: String, line: String): IO[Unit] =
    IO(println(s"[$testName] $line"))

  def startContainerWithLogs(
    container: GenericContainer[_],
    loggerName: String
  ): GenericContainer[_] = {
    container.start()
    val logger = LoggerFactory.getLogger(loggerName)
    val logs = new Slf4jLogConsumer(logger)
    container.followOutput(logs)
    container
  }

  def waitWhile[A](
    a: A,
    condition: A => Boolean,
    maxDelay: FiniteDuration
  ): IO[Boolean] = {
    val retryPolicy = RetryPolicies.limitRetriesByCumulativeDelay(
      maxDelay,
      RetryPolicies.capDelay[IO](
        2.second,
        RetryPolicies.fullJitter[IO](1.second)
      )
    )

    IO(condition(a)).retryingOnFailures(
      result => IO(!result),
      retryPolicy,
      (_, _) => IO.unit
    )
  }

  /** Return a list of config parameters from a raw JSON string. */
  def getConfigParameters(config: String): List[String] = {
    val parsed: Json = parser.parse(config).valueOr { case failure =>
      throw new IllegalArgumentException("Can't parse JSON", failure.underlying)
    }

    def flatten(value: Json): Option[List[(String, Json)]] =
      value.asObject.map(
        _.toList.flatMap {
          case (k, v) => flatten(v) match {
            case None => List(k -> v)
            case Some(fields) => fields.map {
              case (innerK, innerV) => s"$k.$innerK" -> innerV
            }
          }
        }
      )

    def withSpaces(s: String): String = if(s.contains(" ")) s""""$s"""" else s

    val fields = flatten(parsed).getOrElse(throw new IllegalArgumentException("Couldn't flatten fields"))

    fields.flatMap {
      case (k, v) if v.isString =>
        List(s"-D$k=${withSpaces(v.asString.get)}")
      case (k, v) if v.isArray =>
        v.asArray.get.toList.zipWithIndex.map {
          case (s, i) if s.isString =>
            s"-D$k.$i=${withSpaces(s.asString.get)}"
          case (other, i) =>
            s"-D$k.$i=${withSpaces(other.toString)}"
        }
      case (k, v) =>
        List(s"-D$k=${withSpaces(v.toString)}")
    }
  }
}
