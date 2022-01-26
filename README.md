# Scala Stream Collector
[![Build Status][build-image]][build-wf]
[![Release][release-image]][releases]
[![License][license-image]][license]


## Introduction

The Scala Stream Collector is an event collector for [Snowplow][snowplow], written in Scala.
It sets a third-party cookie, allowing user tracking across domains.

The Scala Stream Collector is designed to be easy to setup and store [Thrift][thrift] Snowplow
events to [Amazon Kinesis][kinesis] and [NSQ][nsq], and is built on top of [akka-http][akka-http].

## Find out more

| Technical Docs             | Setup Guide          | Roadmap                     | Contributing              |
|----------------------------|----------------------|-----------------------------|---------------------------|
| ![i1][techdocs-image]      | ![i2][setup-image]   | ![i3][roadmap-image]        | ![i4][contributing-image] |
| [Technical Docs][techdocs] | [Setup Guide][setup] | [Snowplow Roadmap][roadmap] | _coming soon_             |

## Copyright and license

The Scala Stream Collector is copyright 2013-2022 Snowplow Analytics Ltd.

Licensed under the [Apache License, Version 2.0][license] (the "License");
you may not use this software except in compliance with the License.

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

[snowplow]: http://snowplowanalytics.com

[thrift]: http://thrift.apache.org
[kinesis]: http://aws.amazon.com/kinesis
[akka-http]: http://doc.akka.io/docs/akka-http/current/scala/http/introduction.html
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

[license-image]: http://img.shields.io/badge/license-Apache--2-blue.svg?style=flat
[license]: http://www.apache.org/licenses/LICENSE-2.0
