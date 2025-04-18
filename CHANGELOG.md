# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
### Changed
### Fixed
### Deprecated
### Removed
### Security

## [3.4.0] - 2025-04-01
### Added
- Add option to create second cookie to store networkUserID that is not http only [snowplow/stream-collector-private#10]

### Changed
- Kinesis: allow larger payloads when SQS buffer is enabled [snowplow/stream-collector-private#6]
- Unset network_userid when SP-Anonymous header is present [snowplow/stream-collector-private#7]
- Bump AWS SDK to v2 [snowplow/stream-collector-private#11]

### Fixed
- Fix execution of QA tests [snowplow/stream-collector-private#6]

### Security
- Bump dependencies [snowplow/stream-collector-private#9]
- Run snyk for kafka collector [snowplow/stream-collector-private#8]

## [3.3.0] - 2024-12-18
### Changed
- Kafka sink use a dedicated thread for potentially blocking send [snowplow/stream-collector-private#3]

### Security
- Add limit for payload allowed sizes [snowplow/stream-collector-private#1]
- Security and license updates [snowplow/stream-collector-private#1]

## [3.2.1] - 2024-11-25
### Changed
- Kafka sink to open fewer threads [#431]
- Explicitly return 408 when timeout is hit [#427]
- Extend default timeouts to match upstream defaults [#426]
- Update workflows to install sbt [#434]

### Fixed
- Remove parts of the cookies that are not valid according to RFC 6265 [#432]
- Disable timeouts on healthcheck calls [#426]

### Removed
- Remove debug http [#434]
- Remove body read timeout feature [#429]

### Security
- Bump aws sdk to 1.12.769 66 [#428]
- Bump azure-identity to 1.13.2 [#428]
- Bump sbt-snowplow-release to 0.3.2 [#428]

## [3.2.0] - 2024-03-11
### Added
- collector-kafka: authenticate with Event Hubs using OAuth2 [#401]

### Changed
- Prevent Kafka sink from blocking [#418]
- Allow setting size limit on line and header length [#417]
- Add debug logging and timeout configurations [#417]
- Add timeout for body parsing [#417]
- Cross compile to scala 2.12
- Log cats-effect warning at debug level [#414]
- Add snowman job for tag builds

### Security
- Bump transitive jnr-posix to 3.1.8 [#419]

## [3.1.2] - 2024-02-22
### Changed
- Improve relative redirect in cookie bounce feature [#413]

## [3.1.1] - 2024-02-20
### Changed
- Upcase cookie header name [#412]

## [3.1.0] - 2024-01-25
### Added
- Add an option to send HSTS header [#408]

## [3.0.1] - 2024-01-10
### Fixed
- Remove unnecessary argument [#407]

## [3.0.0] - 2024-01-08
### Changed
- Add mandatory SLULA license acceptance flag (close #405)
- Remove unused warmup config section
- Use shortname for collector name (close #403)
- Add statsd metrics reporting (close #404)
- Add support for Do Not Track cookie (close #400)
- Add crossdomain.xml support (close #399)
- Add http root response (close #397)
- Deploy 2.13 scala assets to GH on CI (close #392)
- Use correct sqs buffer queue name with Kinesis bad sink (close #393)
- Sbt project modernization (close #361)
- Update the Pubsub UserAgent format (close #362)
- Add separate good/bad sink configurations (close #388)
- Add Kafka sink healthcheck (close #387)
- Make maxConnections and idleTimeout configurable (close #386)
- Add support for handling /robots.txt (close #385)
- Set installation id (close #384)
- Set maxBytes in the NsqSink (close #383)
- Add http4s Kafka support (close #382)
- Add http4s NSQ support (close #348)
- Add telemetry support (close #381)
- Use Blaze as default http4s backend (close #380)
- Add http4s SQS sink (close #378)
- Add http4s Kinesis sink (close #379)
- Add iglu routes spec (close #377)
- Add http4s PubSub sink (close #376)
- Add http4s SSL support (close #374)
- Add http4s redirect support (close #373)
- Load config (close #326)
- Add http4s anonymous tracking (close #372)
- Add http4s CORS support (close #371)
- Add http4s pixel endpoint (close #370)
- Add http4s GET and HEAD endpoints (close #369)
- Configure set-cookie header (close #368)
- Add test for the stdout sink (close #367)
- Add http4s POST endpoint (close #366)
- Add http4s graceful shutdown (close #365)
- Add http4s module (close #364)
- Add Snowplow Limited Use License (close #346)

## [2.10.0] - 2023-11-08
### Changed
- Update the Pubsub UserAgent format [#362]
- Bump sbt-snowplow-release to 0.3.1 [#363]

## [2.9.2] - 2023-08-25
### Changed
- Add ability to set custom tags for statsd metrics [#340]

## [2.9.1] - 2023-08-03
### Changed
- PubSub: use debug for logging the inserts [#321]
- Run background check for Kinesis if it is made unhealthy and SQS buffer is activated [#315]

## [2.9.0] - 2023-04-11
### Changed
- Bump protobuf-java to 3.21.7 [#308]
- PubSub: add second layer of retry [#304]
- Replace set-output in Github Actions [#305]
- Make MaxRetries configurable [#295]
- Use sbt-snowplow-release to build docker images [#302]
- Update /sink-health endpoint logic [#276]
- Integration tests should work with both regular and distroless project [#301]
- Scan Docker images with Snyk container monitor in deploy.yml [#296]
- Add integration tests for Set-Cookie [#287]
- Add integration test for doNotTrackCookie [#289]
- Add integration test for X-Forwarded-For [#288]
- Add integration test for custom paths [#286]
- Add integration test for /health endpoint [#285]
- Kinesis: add integration tests [#283]
- Validate cookie.fallbackDomain config option on startup [#278]
- PubSub: add integration tests [#274]
- PubSub: make it possible to use PubSub emulator [#270]
- Put MaxBytes in default application.conf instead of hard coding it [#272]

## [2.8.2] - 2022-11-03
### Changed
- Ensure docker images have latest libexpat version [#268]

## [2.8.1] - 2022-10-28
### Changed
- Bump aws sdk to 1.12.327 [#266]
- Warmup process should iterate until success [#264]
- Health endpoint should return 503 during warmup phase [#263]

## [2.8.0] - 2022-09-27
### Changed
- Add RabbitMQ asset [#251]

## [2.7.1] - 2022-09-06
### Changed
- Ensure docker image has latest zlib1g version [#254]

## [2.7.0] - 2022-07-27
### Changed
- Send warmup requests to self on startup [#249]

## [2.6.3] - 2022-07-21
### Changed
Ensure docker image has latest libfreetype6 version [#247]

## [2.6.2] - 2022-07-07
### Changed
- Ensure docker image has openssl version >= 1.1.1n-0+deb11u3 [#244]

## [2.6.1] - 2022-06-13
### Changed
- Reduce number of error messages in kinesis collector logs [#242]
- Bump log4j to 2.17.2 [#241]
- Remove sbt-dependency-graph from plugins.sbt [#235]
- Bump jackson-databind to 2.12.7 [#240]
- Bump aws-java-sdk to 1.12.238 [#239]
- Bump google-cloud-pubsub to 1.119.1 [#238]
- Bump jnr-unixsocket to 0.38.17 [#237]
- Bump akka-http-metrics-datadog to 1.7.1 [#236]

## [2.6.0] - 2022-04-22
### Changed
- Replace all metrics implementations with statsd [#223]
- Enable AWS MSK IAM Authentication [#232]
- Remove warning about missing config option [#222]
- Inspect X-Forwarded-Proto for http -> https redirects [#221]
- Change docker base image to eclipse-temurin:11-jre-focal [#228]
- Publish distroless docker image [#229]

## [2.5.0] - 2022-01-31
### Changed
- Use shorter app name [#217]
- Update copyright notices to 2022 [#216]
- Disable warnings for illegal headers [#178]
- Change default telemetry url [#209]
- Add configuration option for allow list for collector redirects [#131]
- Increase default value of pubsub backoffPolicy totalBackoff [#187]
- Move logging of thread pool creation out of KinesisSink [#129]
- CollectorServiceSpec should test number of events written to sink [#188]
- Improve graceful shutdown [#12]

## [2.4.5] - 2021-12-22
### Changed
- Fix how headers are stringified in the collector payload [#210]

## [2.4.4] - 2021-12-19
### Changed
- Bump log4j-core to 2.17.0 [#206]

## [2.4.3] - 2021-12-14
### Changed
- Fix log4j-core version to 2.16.0 [#195]
- Bump slf4j to 1.7.32 [#196]
- Bump joda-time to 2.10.13 [#198]
- Bump aws-java-sdk to 1.12.128 [#199]
- Bump google-cloud-pubsub to 1.115.0 [#200]
- Bump libthrift to 0.15.0 [#201]
- Bump sbt to 1.5.6 [#202]

## [2.4.2] - 2021-12-08
### Changed
- Fix docs link in README [#192]
- Bump akka-http to 2.4.1 [#193]

## [2.4.1] - 2021-10-20
### Changed
- OSS terraform modules unique id not propagated into telemetry event [#182]

## [2.4.0] - 2021-10-19
### Changed
- Make it possible to configure the collector without a file [#173]
- Add telemetry [#167]
- Handle LimitExceededException when testing if stream exists [#174]
- Include aws-java-sdk-sts to enable web token authentication [#169]
- Use sbt-dynver to set application version [#166]
- Publish arm64 and amd64 docker images [#165]
- Change docker base image to adoptopenjdk:11-jre-hotspot-focal [#164]
- Use JRE defaults for https configuration [#163]
- Bump akka-http to 10.2.6 [#162]
- Bump akka to 2.6.16 [#161]

## [2.3.1] - 2021-08-12
### Changed
- Bump pubsub to 2.113.7 [#158]
- Bump jackson-databind to 2.10.5.1 on nsq module [#157]
- Add cn-northwest-1 to list of custom endpoints [#152]
- Bump jackson-dataformat-cbor to 2.11.4 [#155]
- Bump snowplow-badrows to 2.1.1 [#154]
- Bump libthrift to 0.14.1 [#153]

## [2.3.0] - 2021-05-25
### Changed
- Add outage protection features to Kinesis, SQS and Pubsub sinks [#132]
- Pass Kinesis partitioning key as SQS message attribute [#146]
- Fix typo in PubSub sink useragent string [#147]
- Use base image from DockerHub [#107]
- Attach jar files to Github releases [#108]
- Remove Bintray from resolutionRepos [#144]
- Bump scopt to 4.0.1 [#143]
- Bump pureconfig to 0.15.0 [#142]
- Bump jackson-databind to 2.9.10.8 [#141]
- Bump json4s-jackson to 3.6.11 [#140]
- Bump specs2-core to 4.11.0 [#139]
- Bump sbt-scalafmt to 2.4.2 [#138]
- Bump sbt-tpolecat to 0.1.17 [#137]
- Bump sbt-buildinfo to 0.10.0 [#136]
- Bump sbt-assembly to 0.15.0 [#135]
- Bump sbt to 1.5.1 [#134]
- Add link to Snowplow's public roadmap in README [#145]

## [2.2.1] - 2021-03-26
### Changed
- Respect SQS batch request limit [#125]
- Set network_userid to empty UUID in anonymous mode to prevent collector_payload_format_violation [#126]

## [2.2.0] - 2021-03-08
### Changed
- Add SQS collector module [#120]
- Serve robots.txt file [#109]

## [2.1.2] - 2021-02-18
### Changed
- Prevent network_userid being captured when SP-Anonymous header is set [#117]

- ## [2.1.1] - 2021-01-28
### Changed
- Emit valid bad rows for size violation and generic error [#113]
- Extend copyright to 2021 [#114]

## [2.1.0] - 2020-12-11
- Do not set cookie if request has SP-Anonymous header [#90]
- Generate BadRow if querystring cannot be parsed [#73]
- Don't crash but warn if Kinesis stream and SQS queue don't exist [#100]
- Bump sbt to 1.4.4 [#105]
- Bump specs2-core to 4.10.5 [#106]
- Migrate from Travis to GH actions [#91]
- Bump to JDK 11 [#92]
- Bump base-debian to 0.2.1 [#72]
- Integrate coursier cache action [#93]
- Fix assembly merge strategy [#97]
- Reorganise imports [#104]
- Update copyright to 2020 [#95]

## [2.0.1] - 2020-11-19
### Changed
- Increase number of Kinesis put retries when surge protection is on [#75]
- Bump scalafmt to 2.3.2 [#87]
- Bump akka to 2.5.32 [#86]
- Bump akka-http to 10.1.12 [#85]
- Bump prometheus-simpleclient to 0.9.0 [#82]
- Bump config to 1.4.1 [#81]
- Bump slf4j to 1.7.30 [#80]
- Bump joda-time to 2.10.8 [#79]
- Remove scalaz7 dependency [#84]
- Remove softwaremill-retry dependency [#83]

## [2.0.0] - 2020-09-15
### Changed
- Disable default redirect [#64]
- Bump vulnerable libs [#56]
- Implement surge protection [#57]
- Add test for extracting a URL-encoded schema from the querystring [#60]
- Add snyk monitor [#52]
- Add DockerHub credentials to .travis.yml [#49]
- Add travis integration [#59]

## [1.0.1 (snowplow/snowplow: 119 Tycho Magnetic Anomaly Two)] - 2020-04-30
### Changed
- Bump to 1.0.1 [snowplow/snowplow#4338]
- Add Snowplow Bintray to resolvers [snowplow/snowplow#4326]
- Publish Docker image for stdout via Travis [snowplow/snowplow#4333]
- Fix config example [snowplow/snowplow#4332]
- Fix incompatible jackson dependencies to enable CBOR [snowplow/snowplow#4266]

## [1.0.0 (snowplow/snowplow: 118 Morgantina)] - 2020-01-16
### Changed
- Extend copyright notice to 2020 [snowplow/snowplow#4261]
- Bump to 1.0.0 [snowplow/snowplow#4193]
- Introduce sbt-scalafmt [snowplow/snowplow#4192]
- Bump sbt-buildinfo to 0.9.0 [snowplow/snowplow#4191]
- Use sbt-tpolecat [snowplow/snowplow#4190]
- Bump sbt-assembly to 0.14.9 [snowplow/snowplow#4189]
- Bump specs2 to 4.5.1 [snowplow/snowplow#4188]
- Bump pureconfig to 0.11.1 [snowplow/snowplow#4187]
- Bump akka to 2.5.19 [snowplow/snowplow#4186]
- Bump prometheus-simpleclient to 0.6.0 [snowplow/snowplow#4184]
- Bump config to 1.3.4 [snowplow/snowplow#4183]
- Bump slf4j to 1.7.26 [snowplow/snowplow#4182]
- Bump joda-time to 2.10.2 [snowplow/snowplow#4181]
- Bump kafka-clients to 2.2.1 [snowplow/snowplow#4180]
- Bump google-cloud-pubsub to 1.78.0 [snowplow/snowplow#4179]
- Bump aws-java-sdk to 1.11.573 [snowplow/snowplow#4178]
- Integrate the size violation bad row type [snowplow/snowplow#4177]
- Bump SBT to 1.3.3 [snowplow/snowplow#4176]
- Bump Scala to 2.12.10 [snowplow/snowplow#4175]

## [0.17.0 (snowplow/snowplow: 117 Biskupin)] - 2019-12-03
### Changed
- Publish docker images for scala-stream-collector to DockerHub [#4237]
- Allow users to disable the default redirect endpoint [snowplow/snowplow#4211]
- Bump Scala version to 2.11.12 [snowplow/snowplow#4206]
- Bump akka-http to 10.1.10 [snowplow/snowplow#4185]
- Add support for TLS port binding and certificate [snowplow/snowplow#4085]
- Remove duplicate section in example hocon config file [snowplow/snowplow#4210]
- Bump to 0.17.0 [snowplow/snowplow#4208]

## [0.16.0 (snowplow/snowplow: 116 Madara Rider)] - 2019-09-12
### Changed
- Add options to configure secure, same-site and http-only for the cookie [snowplow/snowplow#3753]
- Allow multiple cookie domains to be used [snowplow/snowplow#3994]
- Provide a way to specify custom path mappings [snowplow/snowplow#4087]
- Send back a Cache-Control header [snowplow/snowplow#4017]
- Add sbt-native-packager and Docker config [snowplow/snowplow#4128]
- Bump Akka HTTP to 10.0.15 [snowplow/snowplow#4131]
- Bump version to 0.16.0 [snowplow/snowplow#4134]

## [0.15.0 (snowplow/snowplow: 113 Filitosa)] - 2019-02-27
### Changed
- Expose Prometheus metrics [snowplow/snowplow#3421]
- Bump kafka client to 2.1.1 [snowplow/snowplow#3981]
- Provide a way to add arbitrary Kafka configuration settings [snowplow/snowplow#3968]
- Add support for an Access-Control-Max-Age header [snowplow/snowplow#3904]
- Allow for the do not track cookie value configuration to be a regex [snowplow/snowplow#3966]
- Showcase the usage of env variables in the configuration example [snowplow/snowplow#3971]
- Extend copyright notice to 2019 [snowplow/snowplow#3997]

## [0.14.0 (snowplow/snowplow: 109 Lambaesis)]  - 2018-08-21
### Changed
- Respect a do not track cookie [snowplow/snowplow#3825]
- Add a way to customize the response from the root path [snowplow/snowplow#3670]
- Support HEAD requests [snowplow/snowplow#3827]
- Allow for multiple domains in crossdomain.xml [snowplow/snowplow#3740]
- Allow overriding of the kinesis endpoint url in the configuration [snowplow/snowplow#3846]
- Turn BufferConfig's byteLimit and recordLimit into longs [snowplow/snowplow#3807]

## [0.13.0 (snowplow/snowplow: 101 Neapolis)] - 2018-03-21
### Changed
- Add Google Cloud PubSub sink [snowplow/snowplow#3047]
- Split into multiple artifacts according to targeted platform [snowplow/snowplow#3621]
- Expose number of requests over JMX [snowplow/snowplow#3637]
- Move cross domain configuration to enabled-style [snowplow/snowplow#3556]
- Truncate events exceeding the configured maximum size into a BadRow [snowplow/snowplow#3587]
- Remove string interpolation false positive warnings [snowplow/snowplow#3623]
- Update config.hocon.sample to support Google Cloud PubSub [snowplow/snowplow#3049]
- Customize useragent for GCP API calls [snowplow/snowplow#3658]
- Bump kafka-clients to 1.0.1 [snowplow/snowplow#3660]
- Bump aws-java-sdk to 1.11.290 [snowplow/snowplow#3665]
- Bump scala-common-enrich to 0.31.0 [snowplow/snowplow#3666]
- Bump SBT to 1.1.1 [snowplow/snowplow#3629]
- Bump sbt-assembly to 0.14.6 [snowplow/snowplow#3667]
- Use sbt-buildinfo [snowplow/snowplow#3626]
- Extend copyright notice to 2018 [snowplow/snowplow#3687]

## [0.12.0 (snowplow/snowplow: 98 Argentomagus)] - 2018-01-05
### Changed
- Make Flash access domains and secure configurable [snowplow/snowplow#2915]
- Add URL redirect replacement macro [snowplow/snowplow#3491]
- Allow use of the originating scheme during cookie bounce [snowplow/snowplow#3512]
- Replace Location header with RawHeader to preserve double encoding [snowplow/snowplow#3546]
- Bump nsq-java-client to 1.2.0 [snowplow/snowplow#3519]
- Document the stdout sink better [snowplow/snowplow#3515]
- Fix stdout sink configuration [snowplow/snowplow#3550]
- Fix scaladoc for 'ipAndPartitionKey' [snowplow/snowplow#3513]

## [0.11.0 (snowplow/snowplow: 96 Zeugma)] - 2017-11-21
### Changed
- Update config.hocon.sample to support NSQ [snowplow/snowplow#3294]
- Add NSQ sink [snowplow/snowplow#2093]
- Make Kinesis, Kafka and NSQ config a coproduct [snowplow/snowplow#3449]
- Keep sending records when the Kinesis stream is resharding [snowplow/snowplow#3453]

## [0.10.0 (snowplow/snowplow: 93 Virunum)] - 2017-10-03
### Changed
- Replace spray by akka-http [snowplow/snowplow#3299]
- Replace argot by scopt [snowplow/snowplow#3298]
- Add support for cookie bounce [snowplow/snowplow#2697]
- Allow raw query params [snowplow/snowplow#3273]
- Add support for the Chinese Kinesis endpoint [snowplow/snowplow#3335]
- Use the DefaultAWSCredentialsProviderChain for Kinesis Sink [snowplow/snowplow#3245]
- Use Kafka callback based API to detect failures to send messages [snowplow/snowplow#3317]
- Make Kafka sink more fault tolerant by allowing retries [snowplow/snowplow#3367]
- Fix incorrect property used for kafkaProducer.batch.size [snowplow/snowplow#3173]
- Configuration decoding with pureconfig [snowplow/snowplow#3318]
- Stop making the assembly jar executable [snowplow/snowplow#3410]
- Add config dependency [snowplow/snowplow#3326]
- Upgrade to Java 8 [snowplow/snowplow#3328]
- Bump Scala version to 2.11 [snowplow/snowplow#3311]
- Bump SBT to 0.13.16 [snowplow/snowplow#3312]
- Bump sbt-assembly to 0.14.5 [snowplow/snowplow#3329]
- Bump aws-java-sdk-kinesis to 1.11 [snowplow/snowplow#3310]
- Bump kafka-clients to 0.10.2.1 [snowplow/snowplow#3325]
- Bump scala-common-enrich to 0.26.0 [snowplow/snowplow#3305]
- Bump iglu-scala-client to 0.5.0 [snowplow/snowplow#3309]
- Bump specs2-core to 3.9.4 [snowplow/snowplow#3308]
- Bump scalaz-core to 7.0.9 [snowplow/snowplow#3307]
- Bump joda-time to 2.9 [snowplow/snowplow#3323]
- Remove commons-codec dependency [snowplow/snowplow#3324]
- Remove snowplow-thrift-raw-event dependency [snowplow/snowplow#3306]
- Remove joda-convert dependency [snowplow/snowplow#3304]
- Remove mimepull dependency [snowplow/snowplow#3302]
- Remove scalazon dependency [snowplow/snowplow#3300]
- Run the unit tests systematically in Travis [snowplow/snowplow#3409]

## [0.9.0 (snowplow/snowplow: 85 Metamorphosis)] - 2016-11-15
### Changed
- Add Kafka sink [snowplow/snowplow#2937]
- Update config.hocon.sample to support Kafka [snowplow/snowplow#2943]
- Move sink.kinesis.buffer to sink.buffer in config.hocon.sample [snowplow/snowplow#2938]

## [0.8.0 (snowplow/snowplow: 84 Steller's Sea Eagle)] - 2016-10-07
### Changed
- Add scala_ into artifact filename in Bintray [snowplow/snowplow#2843]
- Use nuid query parameter value to set the 3rd party network id cookie [snowplow/snowplow#2512]
- Configurable cookie path [snowplow/snowplow#2528]
- Call Config.resolve() to resolve environment variables in hocon [snowplow/snowplow#2879]

## [0.7.0 (snowplow/snowplow: 80 Southern Cassowary)] - 2016-05-30
### Changed
- Increase tolerance of timings in tests [snowplow/snowplow#2614]
- Send nonempty response to POST requests [snowplow/snowplow#2606]
- Crash when unable to find stream instead of hanging [snowplow/snowplow#2583]
- Stop using deprecated Config.getMilliseconds method [snowplow/snowplow#2570]
- Move example configuration file to examples folder [snowplow/snowplow#2566]
- Upgrade the log level for reports of stream nonexistence from INFO to ERROR [snowplow/snowplow#2384]
- Crash rather than hanging when unable to bind to the supplied port [snowplow/snowplow#2551]
- Bump Spray version to 1.3.3 [snowplow/snowplow#2522]
- Bump Scala version to 2.10.5 [snowplow/snowplow#2565]
- Fix omitted string interpolation [snowplow/snowplow#2561]

## [0.6.0 (snowplow/snowplow: 78 Great Hornbill)] - 2016-03-15
### Changed
- Added Scala Common Enrich as a library dependency [snowplow/snowplow#2153]
- Added click redirect mode [snowplow/snowplow#549]
- Configured the ability to use IP address as partition key [snowplow/snowplow#2331]
- Converted bad rows to new format [snowplow/snowplow#2006]
- Shared a single thread pool for all writes to Kinesis [snowplow/snowplow#2369]
- Specified UTF-8 encoding everywhere [snowplow/snowplow#2147]
- Made cookie name customizable, thanks @kazjote! [snowplow/snowplow#2474]
- Added boolean collector.cookie.enabled setting [snowplow/snowplow#2488]
- Made backoffPolicy fields macros [snowplow/snowplow#2518]
- Updated AWS credentials to support iam/env/default not cpf [snowplow/snowplow#1518]

## [0.5.0 (snowplow/snowplow: 67 Bohemian Waxwing)] - 2015-07-13
### Changed
- Stdout bad sink now prints to stderr [snowplow/snowplow#1799]
- Added splitter for large event arrays [snowplow/snowplow#941]
- Increased maximum record size from 50kB to 1MB [snowplow/snowplow#1753]
- Added tests for splitting large requests [snowplow/snowplow#1683]
- Updated bad rows to include timestamp [snowplow/snowplow#1681]
- Handled case where IP is not present [snowplow/snowplow#1680]
- Did some reorganisation and refactoring of the project [snowplow/snowplow#1678]
- Added json4s dependency [snowplow/snowplow#1673]
- Added bad stream [snowplow/snowplow#1502]

## [0.4.0 (snowplow/snowplow: 65 Scarlet Rosefinch)] - 2015-05-08
### Changed
- Bumped Scalazon to 0.11 [snowplow/snowplow#1504]
- Added support for PutRecords API [snowplow/snowplow#1227]
- Added CORS support [snowplow/snowplow#1165]
- Added CORS-style support for ActionScript3 Tracker [snowplow/snowplow#1331]
- Added ability to disable third-party cookies [snowplow/snowplow#1363]
- Removed automatic creation of stream [snowplow/snowplow#1464]
- Added macros to config.hocon.sample [snowplow/snowplow#1471]
- Logged the name of the stream to which records are written [snowplow/snowplow#1503]
- Added shutdown hook to send stored events [snowplow/snowplow#1535]
- Added configurable exponential backoff with jitter [snowplow/snowplow#1592]

## [0.3.0 (snowplow/snowplow: 60 Bee Hummingbird)] - 2015-02-03
### Changed
- Started sending CollectorPayloads instead of SnowplowRawEvents [snowplow/snowplow#1226]
- Added support for POST requests [snowplow/snowplow#187]
- Added support for any {api-vendor}/{api-version} for GET and POST [snowplow/snowplow#652]
- Stopped decoding URLs [snowplow/snowplow#1217]
- Changed 1x1 pixel response to use a stable GIF [snowplow/snowplow#1260]
- Renamed default.conf to config.hocon.sample [snowplow/snowplow#1243]
- Started using ThreadLocal to handle Thrift serialization, thanks @denismo and @pkallos! [snowplow/snowplow#1254]
- Added healthcheck for load balancers, thanks @duncan! [snowplow/snowplow#1360]

## [0.2.0 (snowplow/snowplow: 0.9.12)] - 2014-11-26
### Changed
- Changed organization to "com.snowplowanalytics" [snowplow/snowplow#1168]
- Made the --config option mandatory [snowplow/snowplow#1128]
- Added ability to set AWS credentials from environment variables [snowplow/snowplow#1116]
- Now enforcing Java 7 for compilation [snowplow/snowplow#1068]
- Increased request character limit to 32768 [snowplow/snowplow#987]
- Improved performance by using Future, thanks @pkallos! [snowplow/snowplow#580]
- Scala Stream Collector, Scala Kinesis Enrich: made endpoint configurable, thanks @sambo1972! [snowplow/snowplow#978]
- Scala Stream Collector, Scala Kinesis Enrich: added support for IAM roles, thanks @pkallos! [snowplow/snowplow#534]
- Scala Stream Collector, Scala Kinesis Enrich: replaced stream list with describe to tighten permissions, thanks @pkallos! [snowplow/snowplow#535]

## [0.2.0 (snowplow/snowplow: 0.9.12)] - 2014-11-26
- Changed organization to "com.snowplowanalytics" [snowplow/snowplow#1168]
- Made the --config option mandatory [snowplow/snowplow#1128]
- Added ability to set AWS credentials from environment variables [snowplow/snowplow#1116]
- Now enforcing Java 7 for compilation [snowplow/snowplow#1068]
- Increased request character limit to 32768 [snowplow/snowplow#987]
- Improved performance by using Future, thanks @pkallos! [snowplow/snowplow#580]
- Scala Stream Collector, Scala Kinesis Enrich: made endpoint configurable, thanks @sambo1972! [snowplow/snowplow#978]
- Scala Stream Collector, Scala Kinesis Enrich: added support for IAM roles, thanks @pkallos! [snowplow/snowplow#534]
- Scala Stream Collector, Scala Kinesis Enrich: replaced stream list with describe to tighten permissions, thanks @pkallos! [snowplow/snowplow#535]


[Unreleased]: https://github.com/snowplow/stream-collector/compare/3.4.0...HEAD
[3.4.0]: https://github.com/snowplow/stream-collector/compare/3.3.0...3.4.0
[3.3.0]: https://github.com/snowplow/stream-collector/compare/3.2.1...3.3.0
[3.2.1]: https://github.com/snowplow/stream-collector/compare/3.2.0...3.2.1
[3.2.0]: https://github.com/snowplow/stream-collector/compare/3.1.2...3.2.0
[3.1.2]: https://github.com/snowplow/stream-collector/compare/3.1.1...3.1.2
[3.1.1]: https://github.com/snowplow/stream-collector/compare/3.1.0...3.1.1
[3.1.0]: https://github.com/snowplow/stream-collector/compare/3.0.1...3.1.0
[3.0.1]: https://github.com/snowplow/stream-collector/compare/3.0.0...3.0.1
[3.0.0]: https://github.com/snowplow/stream-collector/compare/2.10.0...3.0.0
[2.10.0]: https://github.com/snowplow/stream-collector/compare/2.9.2...2.10.0
[2.9.2]: https://github.com/snowplow/stream-collector/compare/2.9.1...2.9.2
[2.9.1]: https://github.com/snowplow/stream-collector/compare/2.9.0...2.9.1
[2.9.0]: https://github.com/snowplow/stream-collector/compare/2.8.2...2.9.0
[2.8.2]: https://github.com/snowplow/stream-collector/compare/2.8.2...2.9.0
[2.8.1]: https://github.com/snowplow/stream-collector/compare/2.8.0...2.8.1
[2.8.0]: https://github.com/snowplow/stream-collector/compare/2.7.1...2.8.0
[2.7.1]: https://github.com/snowplow/stream-collector/compare/2.7.0...2.7.1
[2.7.0]: https://github.com/snowplow/stream-collector/compare/2.6.3...2.7.0
[2.6.3]: https://github.com/snowplow/stream-collector/compare/2.6.2...2.6.3
[2.6.2]: https://github.com/snowplow/stream-collector/compare/2.6.1...2.6.2
[2.6.1]: https://github.com/snowplow/stream-collector/compare/2.6.0...2.6.1
[2.6.0]: https://github.com/snowplow/stream-collector/compare/2.5.0...2.6.0
[2.5.0]: https://github.com/snowplow/stream-collector/compare/2.4.5...2.5.0
[2.4.5]: https://github.com/snowplow/stream-collector/compare/2.4.4...2.4.5
[2.4.4]: https://github.com/snowplow/stream-collector/compare/2.4.3...2.4.4
[2.4.3]: https://github.com/snowplow/stream-collector/compare/2.4.2...2.4.3
[2.4.2]: https://github.com/snowplow/stream-collector/compare/2.4.1...2.4.2
[2.4.1]: https://github.com/snowplow/stream-collector/compare/2.4.0...2.4.1
[2.4.0]: https://github.com/snowplow/stream-collector/compare/2.3.1...2.4.0
[2.3.1]: https://github.com/snowplow/stream-collector/compare/2.3.0...2.3.1
[2.3.0]: https://github.com/snowplow/stream-collector/compare/2.2.1...2.3.0
[2.2.1]: https://github.com/snowplow/stream-collector/compare/2.2.0...2.2.1
[2.2.0]: https://github.com/snowplow/stream-collector/compare/2.1.2...2.2.0
[2.1.2]: https://github.com/snowplow/stream-collector/compare/2.1.1...2.1.2
[2.1.1]: https://github.com/snowplow/stream-collector/compare/2.1.0...2.1.1
[2.1.0]: https://github.com/snowplow/stream-collector/compare/2.0.1...2.1.0
[2.0.1]: https://github.com/snowplow/stream-collector/compare/2.0.0...2.0.1
[2.0.0]: https://github.com/snowplow/stream-collector/compare/1.0.1...2.0.0
[1.0.1 (snowplow/snowplow: 119 Tycho Magnetic Anomaly Two)]: https://github.com/snowplow/stream-collector/releases
[1.0.0 (snowplow/snowplow: 118 Morgantina)]: https://github.com/snowplow/stream-collector/releases
[0.17.0 (snowplow/snowplow: 117 Biskupin)]: https://github.com/snowplow/stream-collector/releases
[0.16.0 (snowplow/snowplow: 116 Madara Rider)]: https://github.com/snowplow/stream-collector/releases
[0.15.0 (snowplow/snowplow: 113 Filitosa)]: https://github.com/snowplow/stream-collector/releases
[0.14.0 (snowplow/snowplow: 109 Lambaesis)]: https://github.com/snowplow/stream-collector/releases
[0.13.0 (snowplow/snowplow: 101 Neapolis)]: https://github.com/snowplow/stream-collector/releases
[0.12.0 (snowplow/snowplow: 98 Argentomagus)]: https://github.com/snowplow/stream-collector/releases
[0.11.0 (snowplow/snowplow: 96 Zeugma)]: https://github.com/snowplow/stream-collector/releases
[0.10.0 (snowplow/snowplow: 93 Virunum)]: https://github.com/snowplow/stream-collector/releases
[0.9.0 (snowplow/snowplow: 85 Metamorphosis)]: https://github.com/snowplow/stream-collector/releases
[0.8.0 (snowplow/snowplow: 84 Steller's Sea Eagle)]: https://github.com/snowplow/stream-collector/releases
[0.7.0 (snowplow/snowplow: 80 Southern Cassowary)]: https://github.com/snowplow/stream-collector/releases
[0.6.0 (snowplow/snowplow: 78 Great Hornbill)]: https://github.com/snowplow/stream-collector/releases
[0.5.0 (snowplow/snowplow: 67 Bohemian Waxwing)]: https://github.com/snowplow/stream-collector/releases
[0.4.0 (snowplow/snowplow: 65 Scarlet Rosefinch)]: https://github.com/snowplow/stream-collector/releases
[0.3.0 (snowplow/snowplow: 60 Bee Hummingbird)]: https://github.com/snowplow/stream-collector/releases
[0.2.0 (snowplow/snowplow: 0.9.12)]: https://github.com/snowplow/stream-collector/releases
[0.2.0 (snowplow/snowplow: 0.9.12)]: https://github.com/snowplow/stream-collector/releases
