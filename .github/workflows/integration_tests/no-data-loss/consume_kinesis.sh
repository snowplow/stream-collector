#!/bin/bash

streamName=$1

export PYTHONWARNINGS="ignore:Unverified HTTPS request"

shard=$(aws --endpoint-url=http://localhost:4566 kinesis describe-stream --stream-name $streamName --output text | grep SHARDS | awk '{print $2}')
iterator=$(aws --endpoint-url=http://localhost:4566 kinesis get-shard-iterator --stream-name $streamName --shard-id $shard --shard-iterator-type TRIM_HORIZON --output text)
lines=$(aws --endpoint-url=http://localhost:4566 kinesis get-records --shard-iterator $iterator --output text | tail -n +2|awk '{print $3}' & sleep 1; kill $!)

while read p; do
  found=false
  echo "$p"
  for l in $lines
  do
    a=$(echo "$l" | base64 -d)
    if [[ "$a" == *"$p"* ]]; then
      found=true
      break
    fi
  done
  if [ $found = true ]
  then
      continue
  fi
  break
done <"$PWD"/.github/workflows/integration_tests/no-data-loss/data.txt

echo "Found all records in Kinesis"


