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
