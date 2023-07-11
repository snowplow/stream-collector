package com.snowplowanalytics.snowplow.collectors.scalastream.e2e

import com.snowplowanalytics.snowplow.collectors.scalastream.e2e.storage.SnowflakeSupport

/**
  * Following environment variables are required to run:
  * - TEST_COLLECTOR_HOST
  * - TEST_SNOWFLAKE_URL (format like: 'jdbc:snowflake://${accountName}.snowflakecomputing.com')
  * - TEST_SNOWFLAKE_WAREHOUSE
  * - TEST_SNOWFLAKE_DATABASE
  * - TEST_SNOWFLAKE_USERNAME
  * - TEST_SNOWFLAKE_PASSWORD
  */
class SnowflakeE2E extends E2EScenarios with SnowflakeSupport
