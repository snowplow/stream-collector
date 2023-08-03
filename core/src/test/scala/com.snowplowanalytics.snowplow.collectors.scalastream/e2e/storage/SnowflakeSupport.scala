package com.snowplowanalytics.snowplow.collectors.scalastream.e2e.storage

import cats.effect.{ContextShift, IO}
import SnowflakeSupport._
import doobie.{Fragment, Transactor}
import doobie.implicits._
import doobie.util.fragment

import java.util.Properties
import scala.concurrent.ExecutionContext

trait SnowflakeSupport extends StorageTarget {

  override def transactor: Transactor[IO] = {
    val props: Properties = new Properties()
    props.put("warehouse", System.getenv(snowflakeWarehouseEnv))
    props.put("db", System.getenv(snowflakeDatabaseEnv))
    props.put("user", System.getenv(snowflakeUsernameEnv))
    props.put("password", System.getenv(snowflakePasswordEnv))
    props.put("timezone", "UTC")

    implicit val ioContextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
    Transactor.fromDriverManager[IO](
      driver = "net.snowflake.client.jdbc.SnowflakeDriver",
      url    = System.getenv(snowflakeUrlEnv),
      props
    )
  }

  override def countEventsWithAppIdQuery(appId: String): fragment.Fragment = {
    val schema = System.getenv(snowflakeSchemaEnv)
    sql"select count(*) from ${Fragment.const0(s"$schema.events")} where app_id = $appId"
  }

  override def storageEnvironmentVariables: List[String] = List(
    snowflakeUrlEnv,
    snowflakeWarehouseEnv,
    snowflakeDatabaseEnv,
    snowflakeSchemaEnv,
    snowflakeUsernameEnv,
    snowflakePasswordEnv
  )

}

object SnowflakeSupport {
  val snowflakeUrlEnv       = "TEST_SNOWFLAKE_URL"
  val snowflakeWarehouseEnv = "TEST_SNOWFLAKE_WAREHOUSE"
  val snowflakeDatabaseEnv  = "TEST_SNOWFLAKE_DATABASE"
  val snowflakeSchemaEnv    = "TEST_SNOWFLAKE_SCHEMA"
  val snowflakeUsernameEnv  = "TEST_SNOWFLAKE_USERNAME"
  val snowflakePasswordEnv  = "TEST_SNOWFLAKE_PASSWORD"
}
