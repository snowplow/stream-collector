.PHONY: kinesis-no-data-loss-integration-up kinesis-no-data-loss-consume integration-down kinesis-no-data-loss kinesis-integration-tests integration-tests

integration-down:
	docker ps -aq | xargs -n 1 docker stop
	docker ps -aq | xargs -n 1 docker rm

# ---------- kinesis
kinesis-no-data-loss-integration-up:
	sh ./integration/init.sh kinesis ./integration/docker-compose.yml integration.hocon ./.github/workflows/integration_tests/no-data-loss/data.txt

kinesis-no-data-loss-consume: kinesis-no-data-loss-integration-up
	./.github/workflows/integration_tests/no-data-loss/consume_kinesis.sh good ./.github/workflows/integration_tests/no-data-loss/data.txt

kinesis-no-data-loss: kinesis-no-data-loss-integration-up kinesis-no-data-loss-consume

kinesis-integration-tests: kinesis-no-data-loss-consume
# ----------

integration-tests: kinesis-integration-tests integration-down