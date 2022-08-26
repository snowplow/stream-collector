#!/bin/bash

streamName=$1

export PYTHONWARNINGS="ignore:Unverified HTTPS request"

shard=$(aws --endpoint-url=http://localhost:4566 kinesis describe-stream --stream-name $streamName --output text | grep SHARDS | awk '{print $2}')
iterator=$(aws --endpoint-url=http://localhost:4566 kinesis get-shard-iterator --stream-name $streamName --shard-id $shard --shard-iterator-type LATEST --output text)

while true
do
  lines=$(aws --endpoint-url=http://localhost:4566 kinesis get-records --shard-iterator $iterator --output text|tail -n +2|awk '{print $3}')
  for l in $lines
  do
    echo $l|base64 -d
    echo -e "\n"
  done
  if [[ -n $lines ]]
  then
    iterator=$(aws --endpoint-url=http://localhost:4566 kinesis get-shard-iterator --stream-name $streamName --shard-id $shard --shard-iterator-type LATEST --output text)
  fi
done
