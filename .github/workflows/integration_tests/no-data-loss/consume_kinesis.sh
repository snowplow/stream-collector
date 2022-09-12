#!/bin/bash

streamName=$1
input_file=$2

export AWS_ACCESS_KEY_ID=foobar
export AWS_SECRET_ACCESS_KEY=foobar

shard=$(aws --endpoint-url=http://localhost:4566 kinesis describe-stream --stream-name $streamName --output text --region eu-central-1 | grep SHARDS | awk '{print $2}')

iterator=$(aws --endpoint-url=http://localhost:4566 kinesis get-shard-iterator --stream-name $streamName --shard-id $shard --shard-iterator-type TRIM_HORIZON --output text --region eu-central-1)
lines=$(aws --endpoint-url=http://localhost:4566 kinesis get-records --shard-iterator $iterator --output text --region eu-central-1 | tail -n +2|awk '{print $3}' & sleep 1)

for l in $lines
  do
  found=false
  while read p; do
    a=$(echo "$l" | base64 -d | tr -d '\0')
    if [[ "$a" == *"$p"* ]]; then
      found=true
      break
    fi
  done <"$input_file"
  if [ $found = true ]
  then
      continue
  fi
  break
done

echo "Found all records in Kinesis"