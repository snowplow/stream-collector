#!/bin/bash

data_file=$1

while read p || [ -n "$p" ]; do
  curl -X POST -i http://0.0.0.0:12345/com.snowplowanalytics.snowplow/tp2 -d "$p"
done < "$data_file"

sleep 5