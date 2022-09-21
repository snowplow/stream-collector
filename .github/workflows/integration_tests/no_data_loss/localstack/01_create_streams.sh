#!bin/bash

awslocal kinesis create-stream --stream-name good --shard-count 1 --region eu-central-1
awslocal kinesis create-stream --stream-name bad --shard-count 1 --region eu-central-1
