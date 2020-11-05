package com.snowplowanalytics.snowplow.collectors.scalastream.grpc
import akka.stream.scaladsl.Source
import akka.NotUsed
import akka.actor.ActorSystem

import scala.concurrent.Future

class CollectorServiceImpl(implicit system: ActorSystem) extends CollectorService {

  override def trackPayload(in: TrackEventRequest): Future[TrackEventResponse] =
    Future.successful(TrackEventResponse.of(true))

  override def streamTrackPayload(in: Source[TrackEventRequest, NotUsed]): Future[TrackEventResponse] =
    Future.successful(TrackEventResponse.of(true))

}
