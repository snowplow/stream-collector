#!/bin/bash

echo "$PWD"/.github/workflows/integration_tests/no-data-loss/data.txt
while read p; do
  curl -X POST -i http://0.0.0.0:12345/com.snowplowanalytics.snowplow/tp2 -d "$p"
done <"$PWD"/../.github/workflows/integration_tests/no-data-loss/data.txt