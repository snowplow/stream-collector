package com.snowplowanalytics.snowplow.collectors.scalastream.grpc

import akka.stream.scaladsl._
import akka.NotUsed
import akka.actor.ActorSystem
import akka.grpc.scaladsl.Metadata
import com.snowplowanalytics.snowplow.collectors.scalastream.Service

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class CollectorServiceImpl(collectorService: Service)(implicit system: ActorSystem) extends CollectorServicePowerApi {
  implicit val ec: ExecutionContext = system.dispatcher

  override def trackPayload(in: TrackPayloadRequest, metadata: Metadata): Future[TrackPayloadResponse] =
    Future.successful(collectorService.grpcResponse(in, metadata))

  override def streamTrackPayload(in: Source[TrackPayloadRequest, NotUsed], metadata: Metadata): Future[TrackPayloadResponse] =
    in.runForeach(collectorService.grpcResponse(_, metadata)).transform {
      case Success(_) => Success(TrackPayloadResponse(success = true))
      case Failure(_) => Success(TrackPayloadResponse(success = false))
    }
}
