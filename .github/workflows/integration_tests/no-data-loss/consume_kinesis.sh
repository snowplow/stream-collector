#!/bin/bash

streamName=$1
input_file=$2

export AWS_ACCESS_KEY_ID=foobar
export AWS_SECRET_ACCESS_KEY=foobar

shard=$(aws --endpoint-url=http://localhost:4566 kinesis describe-stream --stream-name $streamName --output text --region eu-central-1 | grep SHARDS | awk '{print $2}')

iterator=$(aws --endpoint-url=http://localhost:4566 kinesis get-shard-iterator --stream-name $streamName --shard-id $shard --shard-iterator-type TRIM_HORIZON --output text --region eu-central-1)
lines=$(aws --endpoint-url=http://localhost:4566 kinesis get-records --shard-iterator $iterator --output text --region eu-central-1 | tail -n +2 | awk '{print $3}' & sleep 1 ; kill $!)

while read p; do
  found=false
  for l in $lines
    do
    a=$(echo "$l" | base64 -d | LC_ALL=C tr '\0' '\n')
    if [[ "$a" == *"$p"* ]]; then
      found=true
      break
    fi
  done
  if [ $found = true ]
  then
      continue
  fi
  echo "Record not found: $(echo "$l" | base64 -d) "
  break
done <"$input_file"

echo "Found all records in Kinesis"