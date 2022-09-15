.PHONY: kinesis-no-data-loss-integration-up kinesis-no-data-loss-consume integration-down kinesis-no-data-loss kinesis-integration-tests integration-tests

integration-down:
	docker ps -aq | xargs -n 1 docker stop
	docker ps -aq | xargs -n 1 docker rm

# -------------------------- kinesis

# --- no data loss
kinesis-no-data-loss-integration-up:
	sh ./integration/init.sh kinesis ./integration/docker-compose.yml integration.hocon

kinesis-no-data-loss-send-data:
	sh ./.github/workflows/integration_tests/no-data-loss/send_data.sh ./.github/workflows/integration_tests/no-data-loss/data.txt

kinesis-no-data-loss-consume:
	./.github/workflows/integration_tests/no-data-loss/consume_kinesis.sh good ./.github/workflows/integration_tests/no-data-loss/data.txt

kinesis-no-data-loss: kinesis-no-data-loss-integration-up kinesis-no-data-loss-send-data kinesis-no-data-loss-consume integration-down

# --- oversized event in bad stream
kinesis-oversized-integration-up:
	sh ./integration/init.sh kinesis ./integration/docker-compose.yml integration.hocon

kinesis-oversized-send-data:
	# generate an oversized string
	openssl rand -base64 1000000 | tr -d '\n' > ./integration/data.txt
	# send the oversized string to the collector
	curl -X POST -i http://0.0.0.0:12345/com.snowplowanalytics.snowplow/tp2 -d "@./integration/data.txt"
	# reduce the oversized string to 1000 characters so it can be searched for in the Kinesis bad stream
	cat ./integration/data.txt | cut -b -1000 >./integration/data_tmp.txt && mv ./integration/data_tmp.txt ./integration/data.txt && rm -rf data_tmp.txt

kinesis-oversized-consume:
	./.github/workflows/integration_tests/no-data-loss/consume_kinesis.sh bad ./integration/data.txt

kinesis-oversized-cleanup:
	rm -rf ./integration/data.txt

kinesis-oversized: kinesis-oversized-integration-up kinesis-oversized-send-data kinesis-oversized-consume integration-down kinesis-oversized-cleanup integration-down

kinesis-integration-tests: kinesis-no-data-loss kinesis-oversized
# --------------------------

integration-tests: kinesis-integration-tests
	echo "ALL TESTS HAVE PASSED SUCCESSFULLY"