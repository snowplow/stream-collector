/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This software is made available by Snowplow Analytics, Ltd.,
  * under the terms of the Snowplow Limited Use License Agreement, Version 1.0
  * located at https://docs.snowplow.io/limited-use-license-1.1
  * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
  * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
  */
package com.snowplowanalytics.snowplow.collector.core

import io.circe.Json

object model {

  /**
    * Case class for holding both good and
    * bad sinks for the Stream Collector.
    */
  final case class Sinks[F[_]](good: Sink[F], bad: Sink[F])

  /**
    * Case class for holding the results of
    * splitAndSerializePayload.
    *
    * @param good All good results
    * @param bad  All bad results
    */
  final case class EventSerializeResult(good: List[Array[Byte]], bad: List[Array[Byte]])

  /**
    * Class for the result of splitting a too-large array of events in the body of a POST request
    *
    * @param goodBatches     List of batches of events
    * @param failedBigEvents List of events that were too large
    */
  final case class SplitBatchResult(goodBatches: List[List[Json]], failedBigEvents: List[Json])
}
