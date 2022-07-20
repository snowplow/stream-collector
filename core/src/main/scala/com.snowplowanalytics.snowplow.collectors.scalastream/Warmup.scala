/*
 * Copyright (c) 2013-2022 Snowplow Analytics Ltd. All rights reserved.
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

      val cxnSettings = ConnectionPoolSettings(system)
        .withMaxConnections(config.maxConnections)
        .withMaxOpenRequests(1 << 30) // must exceed maxConnections and must be a power of 2
        .withMaxRetries(0)

      Source(1 to config.numRequests)
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
            s"Finished warming up $interface:$port. Sent ${config.numRequests} requests with $numFails failures."
          )
        }
    } else Future.successful(())
}
