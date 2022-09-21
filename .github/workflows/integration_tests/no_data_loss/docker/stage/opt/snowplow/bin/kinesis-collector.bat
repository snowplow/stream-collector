@REM Forwarder script

@echo off

%0\..\snowplow-stream-collector -main com.snowplowanalytics.snowplow.collectors.scalastream.KinesisCollector %*
