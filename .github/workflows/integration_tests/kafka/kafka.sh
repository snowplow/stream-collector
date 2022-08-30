#!/bin/bash

(cd integration && docker-compose -f ./docker-compose.yml up -d)
sleep 10
(cd integration && docker-compose logs broker)

echo "waiting for kafka to be ready..."

docker exec broker cub kafka-ready -b broker:9092 1 120

echo "kafka is ready, creating topics..."

docker exec broker kafka-topics --bootstrap-server broker:9092 --create --topic good && \
docker exec broker kafka-topics --bootstrap-server broker:9092 --create --topic bad

echo "topics created, running collector..."

docker run --network="integration_net1" --name collector -d -v "$PWD"/.github/workflows/kafka-collector-config:/snowplow/config -p 12345:12345 snowplow/scala-stream-collector-kafka:0.0.0 --config /snowplow/config/config.hocon

echo "waiting for collector to start..."
sleep 30

docker ps

echo "sending event to collector..."
curl -X POST -i http://0.0.0.0:12345/com.snowplowanalytics.snowplow/tp2  -d 'test' 2>&1 | grep -q 'Set-Cookie'

docker logs collector

echo "consuming from kafka broker, good topic"
timeout 30 docker exec -w /bin broker "kafka-console-consumer" --topic=good --from-beginning --bootstrap-server 0.0.0.0:9092 | grep -q "test"

echo "stopping docker containers"
docker stop $(docker ps -aq)
