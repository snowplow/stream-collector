package com.snowplowanalytics.snowplow.collector.core

trait AppInfo {
  def name: String
  def moduleName: String
  def version: String
  def dockerAlias: String
}
