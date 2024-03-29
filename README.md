# Scala Stream Collector
[![Build Status][build-image]][build-wf]
[![Release][release-image]][releases]
[![License][license-image]][license]


## Introduction

Stream Collector receives raw [Snowplow][snowplow] events sent over HTTP by trackers or webhooks. It serializes them to a [Thrift][thrift] record format, and then writes them to one of supported sinks like [Amazon Kinesis][kinesis], [Google PubSub][pubsub], [Apache Kafka][kafka], [Amazon SQS][sqs], [NSQ][nsq].
The Stream Collector supports cross-domain Snowplow deployments, setting a user_id (used to identify unique visitors) server side to reliably identify the same user across domains.

## Find out more

| Technical Docs             | Setup Guide          | Roadmap                     | Contributing              |
|----------------------------|----------------------|-----------------------------|---------------------------|
| ![i1][techdocs-image]      | ![i2][setup-image]   | ![i3][roadmap-image]        | ![i4][contributing-image] |
| [Technical Docs][techdocs] | [Setup Guide][setup] | [Snowplow Roadmap][roadmap] | _coming soon_             |

## Copyright and license

Copyright (c) 2023-present Snowplow Analytics Ltd. All rights reserved.

Licensed under the [Snowplow Limited Use License Agreement][license]. _(If you are uncertain how it applies to your use case, check our answers to [frequently asked questions][faq].)_

[snowplow]: http://snowplowanalytics.com

[thrift]: http://thrift.apache.org
[kinesis]: http://aws.amazon.com/kinesis
[pubsub]: https://cloud.google.com/pubsub/
[sqs]: https://aws.amazon.com/sqs/
[nsq]: http://nsq.io/

[techdocs-image]: https://d3i6fms1cm1j0i.cloudfront.net/github/images/techdocs.png
[setup-image]: https://d3i6fms1cm1j0i.cloudfront.net/github/images/setup.png
[roadmap-image]: https://d3i6fms1cm1j0i.cloudfront.net/github/images/roadmap.png
[contributing-image]: https://d3i6fms1cm1j0i.cloudfront.net/github/images/contributing.png

[techdocs]: https://docs.snowplowanalytics.com/docs/pipeline-components-and-applications/stream-collector/
[setup]: https://docs.snowplowanalytics.com/docs/getting-started-on-snowplow-open-source/
[roadmap]: https://github.com/snowplow/snowplow/projects/7
[contributing]: https://docs.snowplowanalytics.com/docs/contributing/

[build-image]: https://github.com/snowplow/stream-collector/workflows/build/badge.svg
[build-wf]: https://github.com/snowplow/stream-collector/actions?query=workflow%3Abuild

[release-image]: https://img.shields.io/github/v/release/snowplow/stream-collector?sort=semver&style=flat
[releases]: https://github.com/snowplow/stream-collector

[license]: https://docs.snowplow.io/limited-use-license-1.0
[license-image]: https://img.shields.io/badge/license-Snowplow--Limited-Use-blue.svg?style=flat

[faq]: https://docs.snowplow.io/docs/contributing/limited-use-license-faq/
