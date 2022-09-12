#!/bin/bash

streamName=$1

export PYTHONWARNINGS="ignore:Unverified HTTPS request"

shard=$(aws --endpoint-url=http://localhost:4566 kinesis describe-stream --stream-name $streamName --output text | grep SHARDS | awk '{print $2}')
iterator=$(aws --endpoint-url=http://localhost:4566 kinesis get-shard-iterator --stream-name $streamName --shard-id $shard --shard-iterator-type TRIM_HORIZON --output text)
lines=$(aws --endpoint-url=http://localhost:4566 kinesis get-records --shard-iterator $iterator --output text | tail -n +2 | awk '{print $3}')

count=0

for l in $lines
do
  ((count = $count + 1))
done

if [ $count = 56 ]; then
  echo "Success!"
  exit 0
else
  echo "Oh, no!"
  exit 1
fi
