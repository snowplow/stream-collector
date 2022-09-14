#!/bin/bash

flavour=$1
resource_docker_compose=$2
config_file=$3
data_file=$4

# run sbt and initialise resources (localstack, kafka, etc.)
bash ./integration/init_resources.sh "$resource_docker_compose"

export PYTHONWARNINGS="ignore:Unverified HTTPS request"

sbt "project $flavour; set Docker / version := \"0.0.0\"" 'Docker / publishLocal'

docker run --name collector -d -e AWS_SECRET_ACCESS_KEY=foobar -e AWS_ACCESS_KEY=foobar --network="integration_net1" -v "$PWD"/integration/config:/snowplow/config -p 12345:12345 snowplow/scala-stream-collector-"$flavour":0.0.0 --config /snowplow/config/"$config_file"

sleep 5

curl --connect-timeout 5 --max-time 10 --retry 5 --retry-delay 0 --retry-max-time 60 --retry-connrefused --output - http://0.0.0.0:12345/com.snowplowanalytics.snowplow/tp2

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