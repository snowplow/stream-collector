#!/bin/bash

flavour=$1
resource_docker_file=$2
config_file=$3
data_file=$4

source "$PWD"/integration/init_resources.sh "$flavour" "$resource_docker_file"

docker run -d -e AWS_SECRET_ACCESS_KEY=foobar -e AWS_ACCESS_KEY=foobar --network="integration_net1" -v "$PWD"/config:/snowplow/config -p 12345:12345 snowplow/scala-stream-collector-"$flavour":0.0.0 --config /snowplow/config/"$config_file"

sleep 5

curl --connect-timeout 5 \
     --max-time 10 \
     --retry 5 \
     --retry-delay 0 \
     --retry-max-time 60 \
     --retry-connrefused \
     http://0.0.0.0:12345/com.snowplowanalytics.snowplow/tp2

exit_status=$?
if [ $exit_status != 0 ]
  then
    echo "Failed to start the collector"
    exit $exit_status
fi

while read p; do
  curl -X POST -i http://0.0.0.0:12345/com.snowplowanalytics.snowplow/tp2 -d "$p"
done <"$data_file"

sleep 5