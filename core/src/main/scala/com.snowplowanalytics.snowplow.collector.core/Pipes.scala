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
package com.snowplowanalytics.snowplow.collector.core

import scala.concurrent.duration.FiniteDuration
import cats.effect.Async
import fs2.{Pipe, Pull}

object Pipes {
  def timeoutOnIdle[F[_]: Async, A](duration: FiniteDuration): Pipe[F, A, A] =
    _.pull.timed { timedPull =>
      def go(timedPull: Pull.Timed[F, A]): Pull[F, A, Unit] =
        timedPull.timeout(duration) >>
          timedPull.uncons.flatMap {
            case Some((Right(elems), next)) => Pull.output(elems) >> go(next)
            case _                          => Pull.done
          }

      go(timedPull)
    }.stream
}
