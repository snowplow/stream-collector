package com.snowplowanalytics.snowplow.collector.core

trait AppInfo {
  def name: String
  def version: String
  def dockerAlias: String
}
