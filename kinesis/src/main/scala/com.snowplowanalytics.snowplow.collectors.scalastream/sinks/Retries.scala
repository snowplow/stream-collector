package com.snowplowanalytics.snowplow.collectors.scalastream.sinks

import cats.Applicative
import com.snowplowanalytics.snowplow.collectors.scalastream.sinks.KinesisSinkConfig.BackoffPolicy
import retry.{RetryPolicies, RetryPolicy}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

object Retries {

  def fullJitter[F[_]: Applicative](config: BackoffPolicy): RetryPolicy[F] =
    capBackoffAndRetries(config, RetryPolicies.fullJitter[F](FiniteDuration(config.minBackoff, TimeUnit.MILLISECONDS)))

  def fibonacci[F[_]: Applicative](config: BackoffPolicy): RetryPolicy[F] =
    capBackoffAndRetries(
      config,
      RetryPolicies.fibonacciBackoff[F](FiniteDuration(config.minBackoff, TimeUnit.MILLISECONDS))
    )

  private def capBackoffAndRetries[F[_]: Applicative](config: BackoffPolicy, policy: RetryPolicy[F]): RetryPolicy[F] = {
    val capped = RetryPolicies.capDelay[F](FiniteDuration(config.maxBackoff, TimeUnit.MILLISECONDS), policy)
    val max    = RetryPolicies.limitRetries(config.maxRetries)
    capped.join(max)
  }

}
