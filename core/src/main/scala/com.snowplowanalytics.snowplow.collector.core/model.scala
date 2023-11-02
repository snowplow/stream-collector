/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
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
