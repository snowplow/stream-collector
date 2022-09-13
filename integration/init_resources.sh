#!/bin/bash

resource_docker_file=$1

docker-compose -f "$resource_docker_file" up -d

sleep 15