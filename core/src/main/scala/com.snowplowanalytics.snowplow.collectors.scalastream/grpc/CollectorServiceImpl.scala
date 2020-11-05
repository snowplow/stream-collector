package com.snowplowanalytics.snowplow.collectors.scalastream.grpc

import akka.stream.scaladsl._
import akka.NotUsed
import akka.actor.ActorSystem
import com.snowplowanalytics.snowplow.collectors.scalastream.Service

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class CollectorServiceImpl(collectorService: Service)(implicit system: ActorSystem) extends CollectorService {
  implicit val ec: ExecutionContext = system.dispatcher

  override def trackPayload(in: TrackPayloadRequest): Future[TrackPayloadResponse] = {
    Future.successful(collectorService.grpcResponse(in))
  }

  override def streamTrackPayload(in: Source[TrackPayloadRequest, NotUsed]): Future[TrackPayloadResponse] =
    in.runForeach(collectorService.grpcResponse).transform {
      case Success(_) => Success(TrackPayloadResponse(success = true))
      case Failure(_) => Success(TrackPayloadResponse(success = false))
    }
}
