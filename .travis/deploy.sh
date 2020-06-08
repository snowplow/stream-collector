#!/bin/sh
set -ex

echo "$DOCKER_PASSWORD" | docker login -u "$DOCKER_USERNAME" --password-stdin

sbt "project kinesis" docker:publish && sbt "project pubsub" docker:publish && sbt "project kafka" docker:publish && sbt "project nsq" docker:publish && sbt "project stdout" docker:publish
