/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
  */
package com.snowplowanalytics.snowplow.collectors.scalastream

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.scaladsl.{Sink, Source}
import akka.actor.ActorSystem

import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

import com.snowplowanalytics.snowplow.collectors.scalastream.model.WarmupConfig

object Warmup {

  private lazy val logger: Logger = LoggerFactory.getLogger(getClass)

  def run(interface: String, port: Int, config: WarmupConfig)(
    implicit ec: ExecutionContext,
    system: ActorSystem
  ): Future[Unit] =
    if (config.enable) {
      logger.info(s"Starting warm up of $interface:$port.  It is expected to see a few failures during warmup.")

      def runNextCycle(counter: Int): Future[Unit] = {
        val maxConnections = config.maxConnections * counter
        val numRequests    = config.numRequests * counter

        val cxnSettings = ConnectionPoolSettings(system)
          .withMaxConnections(maxConnections)
          .withMaxOpenRequests(Integer.highestOneBit(maxConnections) * 2) // must exceed maxConnections and must be a power of 2
          .withMaxRetries(0)

        Source(1 to numRequests)
          .map(_ => (HttpRequest(uri = s"/health"), ()))
          .via(Http().cachedHostConnectionPool[Unit](interface, port, cxnSettings))
          .map(_._1)
          .runWith(Sink.seq)
          .map { results =>
            val numFails = results.count(_.isFailure)
            results
              .collect {
                case Failure(e) => e.getMessage
              }
              .toSet
              .foreach { message: String =>
                logger.info(message)
              }

            logger.info(
              s"Finished warmup cycle $counter of $interface:$port with $maxConnections max client TCP connections. Sent ${numRequests} requests with $numFails failures."
            )
            numFails
          }
          .flatMap { numFails =>
            if (numFails > 0 || counter >= config.maxCycles) {
              logger.info(s"Finished all warmup cycles of $interface:$port")
              Future.successful(())
            } else
              runNextCycle(counter + 1)
          }
      }

      runNextCycle(1)
    } else Future.successful(())

}
