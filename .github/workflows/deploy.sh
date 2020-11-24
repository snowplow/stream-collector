#!/bin/bash

set -ex

tag=$1
project_version=$(sbt "project core" version -Dsbt.log.noformat=true | perl -ne 'print "$1\n" if /info.*(\d+\.\d+\.\d+[^\r\n]*)/' | tail -n 1 | tr -d '\n')

if [[ "${tag}" = *"${project_version}" ]]; then
  echo "Building and publishing assets (version $project_version)"
  echo "$DOCKER_PASSWORD" | docker login -u "$DOCKER_USERNAME" --password-stdin
  sbt "project kinesis" docker:publish && sbt "project pubsub" docker:publish && sbt "project kafka" docker:publish && sbt "project nsq" docker:publish && sbt "project stdout" docker:publish
else
  echo "Tag version '${tag}' doesn't match version in scala project ('${project_version}'). Aborting!"
  exit 1
fi
