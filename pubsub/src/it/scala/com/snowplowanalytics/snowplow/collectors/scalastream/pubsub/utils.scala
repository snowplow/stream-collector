/*
 * Copyright (c) 2022-2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.collectors.scalastream.pubsub

import org.apache.thrift.TDeserializer

import io.circe.parser

import cats.implicits._

import cats.effect.IO

import com.snowplowanalytics.snowplow.badrows.BadRow

import com.snowplowanalytics.iglu.core.SelfDescribingData
import com.snowplowanalytics.iglu.core.circe.implicits._

import com.snowplowanalytics.snowplow.CollectorPayload.thrift.model1.CollectorPayload

object utils {

  val maxBytes = 10000

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

  def printBadRows(testName: String, badRows: List[BadRow]): Unit = {
    println(s"[$testName] Bad rows:")
    badRows.foreach(br => println(s"[$testName] ${br.compact}"))
  }

  def log(testName: String, line: String): IO[Unit] =
    IO(println(s"[$testName] $line"))
}
