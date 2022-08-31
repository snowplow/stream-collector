#!/bin/bash

(cd integration && docker-compose -f ./docker-compose.yml up -d)
sleep 10
(cd integration && docker-compose logs broker)

echo "waiting for kafka to be ready..."

sleep 5

echo "running collector..."

docker run --network="integration_net1" --name collector -d -v "$PWD"/.github/workflows/kafka-collector-config:/snowplow/config -p 12345:12345 snowplow/scala-stream-collector-kafka:0.0.0 --config /snowplow/config/config.hocon

echo "waiting for collector to start..."
sleep 5

docker logs collector

docker network ls
docker network inspect integration_net1
docker ps

echo "sending event to collector..."
curl -X POST -i http://0.0.0.0:12345/com.snowplowanalytics.snowplow/tp2  -d 'test' 2>&1 | grep -q 'Set-Cookie'

docker logs collector

echo "consuming from kafka broker, good topic"
timeout 5 docker exec -w /bin broker kafka-console-consumer.sh --topic=good --from-beginning --bootstrap-server 0.0.0.0:9092 | grep -q "test"

echo "stopping docker containers"
docker stop $(docker ps -aq)

