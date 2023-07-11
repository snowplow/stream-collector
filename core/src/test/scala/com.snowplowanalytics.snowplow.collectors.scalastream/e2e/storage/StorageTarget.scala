package com.snowplowanalytics.snowplow.collectors.scalastream.e2e.storage

import cats.effect.IO
import doobie.util.fragment
import doobie.util.transactor.Transactor

trait StorageTarget {

  def transactor: Transactor[IO]

  def countEventsWithAppIdQuery(appId: String): fragment.Fragment

  def storageEnvironmentVariables: List[String]

}
