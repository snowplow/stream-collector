/*
 * Copyright (c) 2013-2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.collectors.scalastream.integration

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, Uri}
import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder
import com.amazonaws.services.kinesis.model.GetRecordsRequest
import com.dimafeng.testcontainers.DockerComposeContainer.ComposeFile
import com.dimafeng.testcontainers.{ContainerDef, DockerComposeContainer, ExposedService, ServiceLogConsumer}
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.scalatest.flatspec.AnyFlatSpec
import org.slf4j.LoggerFactory
import org.testcontainers.containers.output.Slf4jLogConsumer

import java.io.File
import scala.util.{Failure, Success}

class NoDataLossKinesisSpec extends AnyFlatSpec with TestContainerForAll {
  val composeFile = ComposeFile(Left(new File(".github/workflows/integration_tests/no_data_loss/docker-compose.yml")))
  val exposedServices =
    List(ExposedService("localhost.localstack.cloud", 4566), ExposedService("snowplow.collector", 12345))

  lazy val lsLogger = LoggerFactory.getLogger(getClass)
  val lsLog         = new Slf4jLogConsumer(lsLogger)

  lazy val cLogger = LoggerFactory.getLogger(getClass)
  val cLog         = new Slf4jLogConsumer(cLogger)

  val logConsumers =
    List(ServiceLogConsumer("localhost.localstack.cloud", lsLog), ServiceLogConsumer("snowplow.collector", cLog))

  override val containerDef: ContainerDef = DockerComposeContainer.Def(
    composeFiles    = composeFile,
    exposedServices = exposedServices
  )

  it should "ensure no data is lost" in withContainers { _ =>
    lazy val kinesisClient = AmazonKinesisClientBuilder
      .standard()
      .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials("test", "test")))
      .withEndpointConfiguration(new EndpointConfiguration("http://localhost:4566", "eu-central-1"))
      .build

    //Akka
    implicit val system           = ActorSystem()
    implicit val executionContext = system.dispatcher

    val payload =
      """{"schema":"iglu:com.snowplowanalytics.snowplow/payload_data/jsonschema/1-0-4","data":[{"vid":"266855","f_ag":"1","f_pdf":"0","f_realp":"0","fp":"697238","res":"9833x5744","nuid":"f74b3744-0c13-43da-a47d-c90f0056fd77","f_fla":"0","cookie":"0","ttm":"1665491973987","f_gears":"1","sid":"549779a7-1c5f-4ba5-80cd-fdbb28c71141","f_wma":"0","e":"pv","f_qt":"1","ip":"255.0.149.152","duid":"duid_Hmv27ScN0u","tnuid":"1d24ae54-7c59-4a13-b497-aaddc3e73b47","eid":"21b1182c-13eb-47ef-a275-d638d4f8d93a","url":"http://www.DRnmLEu.ru:52254/Pywymm6aH25reP2","refr":"http://www.umSniaP.net:44178/gz1zk9bcMRAursc","ua":"Mozilla/5.0 (Linux; U; Android 2.2; en-us; Nexus One Build/FRF91) AppleWebKit/533.1 (KHTML, like Gecko) Version/4.0 Mobile Safari/533.1","aid":"aid_MyxjB054y0","cx":"eyJzY2hlbWEiOiJpZ2x1OmNvbS5zbm93cGxvd2FuYWx5dGljcy5zbm93cGxvdy9jb250ZXh0cy9qc29uc2NoZW1hLzEtMC0wIiwiZGF0YSI6W3sic2NoZW1hIjoiaWdsdTpjb20ubXBhcnRpY2xlLnNub3dwbG93L3B1c2hyZWdpc3RyYXRpb25fZXZlbnQvanNvbnNjaGVtYS8xLTAtMCIsImRhdGEiOnsibmFtZSI6IjNJTWNCaXJyTktYOUxRaFZEVjlJOXYwU1lSIiwicmVnaXN0cmF0aW9uVG9rZW4iOiJhSW9HZ2t2eWFYVW1scmZDeGNUWjNndiJ9fSx7InNjaGVtYSI6ImlnbHU6Y29tLnNlZ21lbnQvc2NyZWVuL2pzb25zY2hlbWEvMS0wLTAiLCJkYXRhIjp7Im5hbWUiOiJ6TzNiMldsWFBCZzdzSTBsY1FzN2pENkFRZWJhMSJ9fSx7InNjaGVtYSI6ImlnbHU6Y29tLnNub3dwbG93YW5hbHl0aWNzLnNub3dwbG93L2NvbnNlbnRfd2l0aGRyYXduL2pzb25zY2hlbWEvMS0wLTAiLCJkYXRhIjp7ImFsbCI6ZmFsc2V9fSx7InNjaGVtYSI6ImlnbHU6Y29tLm1wYXJ0aWNsZS5zbm93cGxvdy9zZXNzaW9uX2NvbnRleHQvanNvbnNjaGVtYS8xLTAtMCIsImRhdGEiOnsiaWQiOiJFZ3pLb2lrdlc4amNuWkVuMmVvS3FqSXIifX0seyJzY2hlbWEiOiJpZ2x1OmNvbS5vcHRpbWl6ZWx5Lm9wdGltaXplbHl4L3N1bW1hcnkvanNvbnNjaGVtYS8xLTAtMCIsImRhdGEiOnsiZXhwZXJpbWVudElkIjo4MDQwMCwidmFyaWF0aW9uTmFtZSI6Ik0iLCJ2YXJpYXRpb24iOjU5NjcwLCJ2aXNpdG9ySWQiOm51bGx9fSx7InNjaGVtYSI6ImlnbHU6Y29tLm9wdGltaXplbHkvdmFyaWF0aW9uL2pzb25zY2hlbWEvMS0wLTAiLCJkYXRhIjp7ImlkIjpudWxsLCJuYW1lIjoiaDFxNHBXYlB4V1RTSGhkdld3MjhkY2NpNzRwZklSalUiLCJjb2RlIjoiRVR3QmF5amxWWU5HYjByIn19LHsic2NoZW1hIjoiaWdsdTpjb20ub3B0aW1pemVseS9zdGF0ZS9qc29uc2NoZW1hLzEtMC0wIiwiZGF0YSI6eyJleHBlcmltZW50SWQiOiI2dzN3SGwiLCJpc0FjdGl2ZSI6dHJ1ZSwidmFyaWF0aW9uSW5kZXgiOm51bGwsInZhcmlhdGlvbklkIjoiNTciLCJ2YXJpYXRpb25OYW1lIjoiSiJ9fSx7InNjaGVtYSI6ImlnbHU6Y29tLm9wdGltaXplbHkvdmlzaXRvci9qc29uc2NoZW1hLzEtMC0wIiwiZGF0YSI6eyJicm93c2VyIjoiQSIsImJyb3dzZXJWZXJzaW9uIjoiVHoiLCJkZXZpY2UiOiJPOHVsWEhrelM0V056ZiIsImRldmljZVR5cGUiOm51bGwsIm1vYmlsZSI6dHJ1ZX19LHsic2NoZW1hIjoiaWdsdTpjb20uZ29vZ2xlLmFuYWx5dGljcy9wcml2YXRlL2pzb25zY2hlbWEvMS0wLTAiLCJkYXRhIjp7InYiOm51bGwsInMiOjU5NDk4OCwidSI6IlptVXVHTE5ZRmdjb2VBdlZVdG5sMUV2WXIyR2hRZzZEczdwY0dMSXNRMVo1aTlIVEx2SXZwT2JPbWQyTWR5S2xIeFVYS3lRdU9nd3ZGOWJMemlKNlR2UXNJZlRmTnlrN1gzWEdNcGFWaWVaU2s1R0FVM2N0and0Y29yZXY2bUI0Z0c5TWJwNkQxSWszRk84YlplWlB1QXJwY1JtMkhWRFdReUI0WnYyUVBVZEV1NjAyR0hBTHg5VjBCSkx6d3g2ZEVmQnZqeWlxeU5vUFBLM1FIOEd1Y0xxNU9Oc1YwdTdhOE9GZGlXU0wxNWg2a3F5V0FvczcxZ3R2aWd0a1NFclMiLCJnaWQiOiJ2IiwiciI6Mzg1MzM5fX0seyJzY2hlbWEiOiJpZ2x1OmNvbS5nb29nbGUuYW5hbHl0aWNzL2Nvb2tpZXMvanNvbnNjaGVtYS8xLTAtMCIsImRhdGEiOnsiX191dG1hIjoiM0xkWTAxckxSZVZlIiwiX191dG1iIjoiViIsIl9fdXRtYyI6IlFFbk5YZnpqc3ZDdyIsIl9fdXRtdiI6IldMd1kyWVRoZE5iIiwiX191dG16IjoibnJadmlTNDdacSIsIl9nYSI6IlIifX0seyJzY2hlbWEiOiJpZ2x1Om9yZy5pZXRmL2h0dHBfY29va2llL2pzb25zY2hlbWEvMS0wLTAiLCJkYXRhIjp7Im5hbWUiOiIxZUs4QnVQbWNHNTJaTkppdkRrbzFvT2tndiIsInZhbHVlIjoiTSJ9fSx7InNjaGVtYSI6ImlnbHU6b3JnLmlldGYvaHR0cF9oZWFkZXIvanNvbnNjaGVtYS8xLTAtMCIsImRhdGEiOnsibmFtZSI6IlVKNXVic1hWQWR1NDUiLCJ2YWx1ZSI6Ik9lajhQMTNkVFVLU1NGQjkifX0seyJzY2hlbWEiOiJpZ2x1OmNvbS5zbm93cGxvd2FuYWx5dGljcy5zbm93cGxvdy91YV9wYXJzZXJfY29udGV4dC9qc29uc2NoZW1hLzEtMC0wIiwiZGF0YSI6eyJ1c2VyYWdlbnRNaW5vciI6IjIiLCJ1c2VyYWdlbnRGYW1pbHkiOiJDaHJvbWUiLCJ1c2VyYWdlbnRNYWpvciI6IjAiLCJvc0ZhbWlseSI6Ik1hYyBPUyBYIiwiZGV2aWNlRmFtaWx5IjoiTWFjIn19LHsic2NoZW1hIjoiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvZGVza3RvcF9jb250ZXh0L2pzb25zY2hlbWEvMS0wLTAiLCJkYXRhIjp7Im9zVHlwZSI6IlNvbGFyaXMiLCJvc1ZlcnNpb24iOiI0MzczIiwib3NTZXJ2aWNlUGFjayI6InAiLCJkZXZpY2VNYW51ZmFjdHVyZXIiOiIzMjIzMjkxODk5NiIsImRldmljZU1vZGVsIjoiOTg0NzM3Mzg5MjAxNDE3MDgwMTU4MzMzMzkiLCJkZXZpY2VQcm9jZXNzb3JDb3VudCI6MjR9fSx7InNjaGVtYSI6ImlnbHU6Y29tLnNub3dwbG93YW5hbHl0aWNzLnNub3dwbG93L2NvbnNlbnRfZG9jdW1lbnQvanNvbnNjaGVtYS8xLTAtMCIsImRhdGEiOnsiaWQiOiJOIiwidmVyc2lvbiI6ImsiLCJuYW1lIjoiQyIsImRlc2NyaXB0aW9uIjoiNFlDOHBaSnA3dlp6bWlCSHNYSzZqV1JDTktCeTdmMm1DTWsydzM5U2FFaXlIdUVrUDgyak9SMG1FdzlpTTZXaUhiSFhTeDFCQVNNRDdaek80U0xlOTlhQmlXN0FRaWZJNXlOZVMyQmxWNWdJS25EWkVVWm8yU2NjYlJLWFk1RVFPellVZkFjS2tpVFZqQVdSTzVHa29sd042ZE5qM2hsR0JjMSJ9fSx7InNjaGVtYSI6ImlnbHU6Y29tLnNub3dwbG93YW5hbHl0aWNzLnNub3dwbG93L2NsaWVudF9zZXNzaW9uL2pzb25zY2hlbWEvMS0wLTEiLCJkYXRhIjp7InVzZXJJZCI6ImRlMDgxOWZkLWI3MGUtNDRlNC1iMzYzLTIzMjY2YTZhZTkxNiIsInNlc3Npb25JZCI6IjM4ODZmN2NjLWZiMDktNDRiZi04MmQ0LTRlZDFlYjkxM2NiNSIsInNlc3Npb25JbmRleCI6MjUwOTg0MTcsInByZXZpb3VzU2Vzc2lvbklkIjoiM2UzMjQ1YmYtN2FmYy00YjljLThjNTAtZWMxMzVlYzA5NzZhIiwic3RvcmFnZU1lY2hhbmlzbSI6IkNPT0tJRV8zIn19LHsic2NoZW1hIjoiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvY2hhbmdlX2Zvcm0vanNvbnNjaGVtYS8xLTAtMCIsImRhdGEiOnsiZm9ybUlkIjoiV24wRUNPcUFxS3Q4VWdOREQ4bUxzQlZ5dW1NSEs0STQiLCJlbGVtZW50SWQiOiJIdUxhdm1YMkRTUXh3VkJLeTdHWGpOSVFXeEI4ZmlXSyIsIm5vZGVOYW1lIjoiSU5QVVQiLCJ0eXBlIjoiZGF0ZXRpbWUiLCJ2YWx1ZSI6IldlSCJ9fV19","f_dir":"1","tna":"tna_wBSVed7ZFA","cs":"x-IBM939","cd":"36025","page":"Do amet dolor elit sed consectetur ipsum sit adipiscing lorem","stm":"1665497556141","f_java":"1","tv":"scala-tracker_1.0.0","vp":"1x8261","ds":"1x10000","p":"app","dtm":"1665501868661","lang":"1","tid":"378009"}]}"""

    val request = HttpRequest()
      .withMethod(HttpMethods.POST)
      .withUri(Uri(s"http://0.0.0.0:12345/com.snowplowanalytics.snowplow/tp2"))
      .withHeaders(List(RawHeader("Accept", "*/*"), RawHeader("Content-Type", "application/json; charset=UTF-8")))
      .withEntity(payload)

    def sendRequest(request: HttpRequest): Unit = {
      val responseFuture = Http().singleRequest(request)
      responseFuture.onComplete {
        case Success(response) => println(s"Got ${response.status} for request $request")
        case Failure(_)        => println(s"Ooops! $request failed!")
      }
    }

    val _ = {
      sendRequest(request)
      Thread.sleep(30000)

      val shardId           = kinesisClient.describeStream("good").getStreamDescription.getShards.get(0).getShardId
      val iterator          = kinesisClient.getShardIterator("good", shardId, "TRIM_HORIZON").getShardIterator
      val getRecordsRequest = new GetRecordsRequest().withShardIterator(iterator)
      val numRecords        = kinesisClient.getRecords(getRecordsRequest).getRecords.size()
      assert(numRecords == 1)
    }
  }
}
