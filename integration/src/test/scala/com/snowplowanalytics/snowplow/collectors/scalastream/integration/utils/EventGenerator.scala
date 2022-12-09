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
package com.snowplowanalytics.snowplow.collectors.scalastream.integration.utils

import com.snowplowanalytics.snowplow.eventgen.tracker.{HttpRequest => RequestStub}
import org.scalacheck.Gen

object EventGenerator {
  def makeStubs(min: Int, max: Int): List[RequestStub] =
    (for {
      n    <- Gen.chooseNum(min, max)
      reqs <- Gen.listOfN(n, RequestStub.gen(1, 5, java.time.Instant.now))
    } yield reqs)
      .apply(org.scalacheck.Gen.Parameters.default, org.scalacheck.rng.Seed(scala.util.Random.nextLong()))
      .getOrElse(List.empty)
}
