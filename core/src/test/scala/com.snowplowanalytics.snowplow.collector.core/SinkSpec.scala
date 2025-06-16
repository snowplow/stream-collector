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

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.effect.testing.specs2.CatsEffect
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import org.specs2.mutable.Specification

import com.snowplowanalytics.snowplow.streams.{ListOfList, Sink => CommonStreamsSink, Sinkable}

import scala.concurrent.duration.DurationLong

class SinkSpec extends Specification with CatsEffect {

  implicit private val logger: Logger[IO] = NoOpLogger[IO]

  override def is = s2"""
  Sink.ofCommonStreamsSink should:
    Report itself as healthy only when the common-streams sink becomes healthy $e1
    Report itself as healthy when the common-streams healthy check recovers from throwing exception $e2
    Report itself as unhealthy if sinking events yields an exception $e3
  """

  def e1 = {

    // A common-streams sink which reports itself as healthy only after 1 hours
    def testSink = new CommonStreamsSink[IO] {
      def sink(batch: ListOfList[Sinkable]): IO[Unit] =
        IO.unit

      def isHealthy: IO[Boolean] =
        IO.realTime.map { t =>
          t > 1.hours
        }
    }

    val io = Sink.ofCommonStreamsSink("name", 42, 1.minute, 1.minute, testSink).use { sink =>
      for {
        _          <- IO.sleep(58.minutes)
        isHealthy1 <- sink.isHealthy
        _          <- IO.sleep(4.minutes)
        isHealthy2 <- sink.isHealthy
      } yield {
        (isHealthy1 must beFalse).and(isHealthy2 must beTrue)
      }
    }
    TestControl.executeEmbed(io)
  }

  def e2 = {

    // A common-streams sink whose health check raises an exception until after 1 hours
    def testSink = new CommonStreamsSink[IO] {
      def sink(batch: ListOfList[Sinkable]): IO[Unit] =
        IO.unit

      def isHealthy: IO[Boolean] =
        IO.realTime.flatMap { t =>
          if (t < 1.hours)
            IO.raiseError(new RuntimeException("boom!"))
          else
            IO.pure(true)
        }
    }

    val io = Sink.ofCommonStreamsSink("name", 42, 1.minute, 1.minute, testSink).use { sink =>
      for {
        _          <- IO.sleep(58.minutes)
        isHealthy1 <- sink.isHealthy
        _          <- IO.sleep(4.minutes)
        isHealthy2 <- sink.isHealthy
      } yield {
        (isHealthy1 must beFalse).and(isHealthy2 must beTrue)
      }
    }
    TestControl.executeEmbed(io)
  }

  def e3 = {

    // A common-streams sink which yields exceptions upon sinking events until after 1 hour
    def testSink = new CommonStreamsSink[IO] {
      def sink(batch: ListOfList[Sinkable]): IO[Unit] =
        IO.realTime.flatMap { t =>
          if (t < 1.hours)
            IO.raiseError(new RuntimeException("boom!"))
          else
            IO.unit
        }

      def isHealthy: IO[Boolean] =
        IO.pure(true)
    }

    val io = Sink.ofCommonStreamsSink("name", 42, 1.minute, 1.minute, testSink).use { sink =>
      for {
        _          <- sink.storeRawEvents(List(Array())).start
        _          <- IO.sleep(58.minutes)
        isHealthy1 <- sink.isHealthy
        _          <- IO.sleep(4.minutes)
        isHealthy2 <- sink.isHealthy
      } yield {
        (isHealthy1 must beFalse).and(isHealthy2 must beTrue)
      }
    }
    TestControl.executeEmbed(io)
  }
}
