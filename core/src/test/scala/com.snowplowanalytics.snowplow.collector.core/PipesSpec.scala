package com.snowplowanalytics.snowplow.collector.core

import scala.concurrent.duration._
import org.specs2.mutable.Specification
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream

class PipesSpec extends Specification {

  "Pipes#timeoutOnIdle" should {
    "allow terminating a stream early when idle" in {
      Stream
        .emits[IO, Int](Vector(1, 2, 3))
        .onComplete(Stream.empty[IO].delayBy(20.seconds))
        .through(Pipes.timeoutOnIdle(100.millis))
        .compile
        .count
        .unsafeRunSync() must beEqualTo(3)
    }
  }
}
