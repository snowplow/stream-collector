# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the Snowplow Stream Collector, a Scala application that receives raw Snowplow events sent over HTTP by trackers or webhooks. It serializes them to Thrift record format and writes them to various sinks (Amazon Kinesis, Google PubSub, Apache Kafka, Amazon SQS, NSQ, or stdout).

The project supports:
- Cross-domain Snowplow deployments with server-side user ID management
- Multiple sink configurations for different streaming platforms
- Compression (GZIP/ZSTD) support
- Comprehensive HTTP handling including CORS, cookies, SSL/TLS, HSTS
- StatsD metrics and telemetry

## Build System and Commands

This is an SBT (Scala Build Tool) project with multi-module structure.

### Essential Commands

- **Build all modules**: `sbt compile`
- **Run tests**: `sbt test`
- **Run integration tests**: `sbt IntegrationTest/test`
- **Build Docker images**: `sbt Docker/publishLocal`
- **Build assembly JARs**: `sbt assembly`

### Module-Specific Commands

- **Build specific sink**: `sbt <sink>/compile` (where `<sink>` is kinesis, kafka, pubsub, sqs, nsq, stdout)
- **Test specific module**: `sbt <module>/test`
- **Run integration tests for sink**: `sbt <sink>/IntegrationTest/test`

### Running the Collector

Each sink variant is packaged separately:
- **Kinesis**: `sbt kinesis/run`
- **Kafka**: `sbt kafka/run`
- **PubSub**: `sbt pubsub/run`
- **SQS**: `sbt sqs/run`
- **NSQ**: `sbt nsq/run`
- **Stdout**: `sbt stdout/run`

## Architecture

### Core Modules

- **core**: Main HTTP server logic, configuration, request handling, and serialization
- **kinesis**: AWS Kinesis sink implementation
- **kafka**: Apache Kafka sink implementation
- **pubsub**: Google Cloud Pub/Sub sink implementation
- **sqs**: AWS SQS sink implementation
- **nsq**: NSQ sink implementation
- **stdout**: Simple stdout sink for testing/development

### Key Components

- **HttpServer** (`core/src/main/scala/.../HttpServer.scala`): HTTP4s-based server with Blaze backend
- **Service** (`core/src/main/scala/.../Service.scala`): Request/response handling and CollectorPayload creation
- **Sinks** (`core/src/main/scala/.../Sinks.scala`): Batching and streaming logic for good/bad events
- **Config** (`core/src/main/scala/.../Config.scala`): Comprehensive configuration model with HOCON support
- **Routes** (`core/src/main/scala/.../Routes.scala`): HTTP route definitions
- **CompressingDequeuer**: Handles compression when enabled

### Data Flow

1. HTTP requests arrive at the collector
2. **Service** extracts data and creates **CollectorPayload** (Thrift format)
3. Payloads are queued and batched by **Sinks**
4. Batched events are written to configured sink (good events) or bad sink (malformed events)
5. Optional compression is applied during batching

### Configuration

- Configuration uses HOCON format
- Example configs in `examples/` directory for each sink type
- Configuration includes sink-specific settings, networking, SSL, cookies, CORS, etc.
- Two configuration styles supported: new (per-sink buffer config) and legacy (shared buffer config)

### Testing Strategy

- **Unit Tests**: Located in `src/test/scala` directories using Specs2
- **Integration Tests**: Located in `src/it/scala` directories using Testcontainers
- Integration tests automatically build and use Docker images
- Example test configurations in `src/it/resources`

### Key Technologies

- **Scala 2.13** with Cats Effect for functional programming
- **HTTP4s** with Blaze server for HTTP handling
- **FS2** for streaming and batching
- **Circe** for JSON/configuration parsing
- **Thrift** for event serialization
- **Docker** for containerized deployment

## Development Notes

- Code uses comprehensive functional programming patterns with Cats Effect
- HTTP4s provides type-safe HTTP handling
- FS2 streams handle event batching and backpressure
- Each sink module depends on core module
- Docker images are built for both regular and distroless variants
- Integration tests require Docker to be available
- The project includes both regular and distroless Docker image variants for reduced attack surface