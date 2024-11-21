# Scala Stream Collector
[![Build Status][build-image]][build-wf]
[![Release][release-image]][releases]
[![License][license-image]][license]


## Introduction

Stream Collector receives raw [Snowplow][snowplow] events sent over HTTP by trackers or webhooks. It serializes them to a [Thrift][thrift] record format, and then writes them to one of the supported sinks like [Amazon Kinesis][kinesis], [Google PubSub][pubsub], [Apache Kafka][kafka], [Amazon SQS][sqs], [NSQ][nsq].
The Stream Collector supports cross-domain Snowplow deployments, setting a `user_id` (used to identify unique visitors) server side to reliably identify the same user across domains.

## Find out more

| Technical Docs             | Setup Guide          | Contributing                 |
|----------------------------|----------------------|------------------------------|
| ![i1][techdocs-image]      | ![i2][setup-image]   | ![i4][contributing-image]    |
| [Technical Docs][techdocs] | [Setup Guide][setup] | [Contributing][contributing] |

## Copyright and license

Copyright (c) 2023-present Snowplow Analytics Ltd. All rights reserved.

Licensed under the [Snowplow Limited Use License Agreement][license]. _(If you are uncertain how it applies to your use case, check our answers to [frequently asked questions][faq].)_

[snowplow]: https://snowplow.io/

[thrift]: http://thrift.apache.org
[kinesis]: http://aws.amazon.com/kinesis
[pubsub]: https://cloud.google.com/pubsub/
[kafka]: http://kafka.apache.org
[sqs]: https://aws.amazon.com/sqs/
[nsq]: http://nsq.io/

[techdocs-image]: https://d3i6fms1cm1j0i.cloudfront.net/github/images/techdocs.png
[setup-image]: https://d3i6fms1cm1j0i.cloudfront.net/github/images/setup.png
[contributing-image]: https://d3i6fms1cm1j0i.cloudfront.net/github/images/contributing.png

[techdocs]: https://docs.snowplow.io/docs/pipeline-components-and-applications/stream-collector/
[setup]: https://docs.snowplow.io/docs/getting-started-on-community-edition/
[contributing]: https://docs.snowplow.io/docs/contributing/

[build-image]: https://github.com/snowplow/stream-collector/workflows/build/badge.svg
[build-wf]: https://github.com/snowplow/stream-collector/actions?query=workflow%3Abuild

[release-image]: https://img.shields.io/github/v/release/snowplow/stream-collector?sort=semver&style=flat
[releases]: https://github.com/snowplow/stream-collector

[license]: https://docs.snowplow.io/limited-use-license-1.0
[license-image]: https://img.shields.io/badge/license-Snowplow--Limited-Use-blue.svg?style=flat

[faq]: https://docs.snowplow.io/docs/contributing/limited-use-license-faq/
