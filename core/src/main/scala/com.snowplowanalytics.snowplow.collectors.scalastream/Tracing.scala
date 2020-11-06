/*
 * Copyright (c) 2013-2020 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, and
 * you may not use this file except in compliance with the Apache License
 * Version 2.0.  You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Apache License Version 2.0 is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream

import io.jaegertracing.{Configuration => JaegerConfiguration}
import io.jaegertracing.internal.propagation.B3TextMapCodec
import io.jaegertracing.zipkin.ZipkinV2Reporter
import io.opentracing.Tracer
import io.opentracing.noop.NoopTracerFactory
import io.opentracing.propagation.Format
import org.slf4j.LoggerFactory
import zipkin2.reporter.okhttp3.OkHttpSender
import zipkin2.reporter.AsyncReporter

import scala.collection.JavaConverters._

import com.snowplowanalytics.snowplow.collectors.scalastream.model.TracerConfig


object Tracing {

  lazy val log = LoggerFactory.getLogger(getClass())

  def tracer(config: TracerConfig): Tracer =
    config match {
      case TracerConfig.Noop =>
        log.debug("Using noop tracer")
        NoopTracerFactory.create
      case j: TracerConfig.Jaeger =>
        log.debug("Using jaeger tracer")
        new JaegerConfiguration(j.serviceName)
          .withReporter {
            val rc = new JaegerConfiguration.ReporterConfiguration
            rc.withSender {
              val sender = new JaegerConfiguration.SenderConfiguration
              j.agentHost.foreach(sender.withAgentHost(_))
              j.agentPort.foreach(sender.withAgentPort(_))
              sender
            }
            rc
          }
          .withSampler {
            val sampler = new JaegerConfiguration.SamplerConfiguration
            j.samplerType.foreach(sampler.withType(_))
            j.samplerParam.foreach(sampler.withParam(_))
            j.managerHostPort.foreach(sampler.withManagerHostPort(_))
            sampler
          }
          .withTracerTags(j.tracerTags.asJava)
          .getTracer
      case z: TracerConfig.Zipkin =>
        log.debug("Using zipkin tracer")
        val b3Codec = new B3TextMapCodec.Builder().build;

        new JaegerConfiguration(z.serviceName)
          .withSampler {
            (new JaegerConfiguration.SamplerConfiguration)
              .withType(z.samplerType)
              .withParam(z.samplerParam)
          }
          .withTracerTags(z.tracerTags.asJava)
          .getTracerBuilder
          .withReporter {
            new ZipkinV2Reporter(AsyncReporter.create(OkHttpSender.create(z.endpoint)))
          }
          .registerInjector(Format.Builtin.HTTP_HEADERS, b3Codec)
          .registerExtractor(Format.Builtin.HTTP_HEADERS, b3Codec)
          .build
    }
}
