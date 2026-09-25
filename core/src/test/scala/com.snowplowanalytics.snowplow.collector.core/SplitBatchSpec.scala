package com.snowplowanalytics.snowplow.collector.core

import org.apache.thrift.TDeserializer

import io.circe.Json
import io.circe.syntax._
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer

import com.snowplowanalytics.snowplow.badrows._

import com.snowplowanalytics.snowplow.collector.thrift.CollectorPayload
import com.snowplowanalytics.snowplow.collector.core.model.SplitBatchResult

import org.specs2.mutable.Specification

class SplitBatchSpec extends Specification {
  val splitBatch: SplitBatch = SplitBatch(TestUtils.appInfo)

  "SplitBatch.split" should {
    "Batch a list of strings based on size" in {
      splitBatch.split(List("a", "b", "c").map(Json.fromString), 9, 1) must_==
        SplitBatchResult(List(List("c"), List("b", "a")).map(_.map(Json.fromString)), Nil)
    }

    "Reject only those strings which are too big" in {
      splitBatch.split(List("1234567", "1", "123").map(Json.fromString), 8, 0) must_==
        SplitBatchResult(List(List("123", "1").map(Json.fromString)), List("1234567").map(Json.fromString))
    }

    "Batch a long list of strings" in {
      splitBatch.split(
        List("123456778901", "123456789", "12345678", "1234567", "123456", "12345", "1234", "123", "12", "1")
          .map(Json.fromString),
        13,
        0
      ) must_==
        SplitBatchResult(
          List(
            List("1", "12", "123"),
            List("1234", "12345"),
            List("123456"),
            List("1234567"),
            List("12345678"),
            List("123456789")
          ).map(_.map(Json.fromString)),
          List("123456778901").map(Json.fromString)
        )
    }
  }

  "SplitBatch.splitAndSerializePayload" should {
    "Serialize an empty CollectorPayload" in {
      val actual = splitBatch.splitAndSerializePayload(new CollectorPayload(), 100, 101L)
      val target = new CollectorPayload()
      new TDeserializer().deserialize(target, actual.good.head)
      target must_== new CollectorPayload()
    }

    "Serialize a CollectorPayload with a UTF-8 String body" in {
      val bodyString = "ABC123😊扫雪机"
      val input      = new CollectorPayload
      input.setBody(ByteBuffer.wrap(bodyString.getBytes(StandardCharsets.UTF_8)))
      val actual = splitBatch.splitAndSerializePayload(input, 100, 101L)
      val target = new CollectorPayload()
      new TDeserializer().deserialize(target, actual.good.head)
      new String(target.getBody, StandardCharsets.UTF_8) must_== bodyString
    }

    "Serialize a CollectorPayload with binary body that is not UTF-8" in {
      val body  = Array(192.toByte, 193.toByte, 245.toByte) // None of these bytes appear in valid UTF-8
      val input = new CollectorPayload
      input.setBody(ByteBuffer.wrap(body))
      val actual = splitBatch.splitAndSerializePayload(input, 100, 101L)
      val target = new CollectorPayload()
      new TDeserializer().deserialize(target, actual.good.head)
      target.bufferForBody() must_== input.bufferForBody()
    }

    "Reject an oversized GET CollectorPayload" in {
      val payload = new CollectorPayload()
      payload.setQuerystring("x" * 1000)
      val actual = splitBatch.splitAndSerializePayload(payload, 100, 1020L)
      val badRow = actual.bad.head
      badRow must beAnInstanceOf[BadRow.SizeViolation]
      val sizeViolation = badRow.asInstanceOf[BadRow.SizeViolation]
      sizeViolation.failure.maximumAllowedSizeBytes must_== 100
      sizeViolation.failure.actualSizeBytes must_== 1019
      sizeViolation.failure.expectation must_== "oversized collector payload: GET requests cannot be split"
      sizeViolation.payload.event must_== "CollectorP"
      sizeViolation.processor shouldEqual Processor(TestUtils.appName, TestUtils.appVersion)
      actual.good must_== Nil
    }

    "Reject an oversized POST CollectorPayload when exceeds max payload size" in {
      val payload = new CollectorPayload()
      payload.setBody(("s" * 1010).getBytes(StandardCharsets.UTF_8))
      val actual = splitBatch.splitAndSerializePayload(payload, 1000, 1000L)
      val badRow = actual.bad.head
      badRow must beAnInstanceOf[BadRow.SizeViolation]
      val sizeViolation = badRow.asInstanceOf[BadRow.SizeViolation]
      sizeViolation.failure.maximumAllowedSizeBytes must_== 1000
      sizeViolation.failure.actualSizeBytes must_== 1029
      sizeViolation
        .failure
        .expectation must_== "oversized collector payload: Payload exceeds max size of 1000. Actual length: 1029"
      sizeViolation
        .payload
        .event must_== "CollectorPayload(schema:null, ipAddress:null, timestamp:0, encoding:null, collector:null, body:73 73"
      sizeViolation.processor shouldEqual Processor(TestUtils.appName, TestUtils.appVersion)
    }

    "Reject an oversized POST CollectorPayload with an unparseable body" in {
      val payload = new CollectorPayload()
      payload.setBody(("s" * 1000).getBytes(StandardCharsets.UTF_8))
      val actual = splitBatch.splitAndSerializePayload(payload, 100, 1020L)
      val badRow = actual.bad.head
      badRow must beAnInstanceOf[BadRow.SizeViolation]
      val sizeViolation = badRow.asInstanceOf[BadRow.SizeViolation]
      sizeViolation.failure.maximumAllowedSizeBytes must_== 100
      sizeViolation.failure.actualSizeBytes must_== 1019
      sizeViolation
        .failure
        .expectation must_== "oversized collector payload: cannot split POST requests which are not json expected json value got 'ssssss...' (line 1, column 1)"
      sizeViolation.payload.event must_== "CollectorP"
      sizeViolation.processor shouldEqual Processor(TestUtils.appName, TestUtils.appVersion)
    }

    "Reject an oversized POST CollectorPayload with a binary body that is not valid UTF-8" in {
      val payload = new CollectorPayload()
      val body    = Array.fill(1000)(192.toByte) // 192.toByte is not valid UTF-8
      payload.setBody(body)
      val actual = splitBatch.splitAndSerializePayload(payload, 100, 1020L)
      val badRow = actual.bad.head
      badRow must beAnInstanceOf[BadRow.SizeViolation]
      val sizeViolation = badRow.asInstanceOf[BadRow.SizeViolation]
      sizeViolation.failure.maximumAllowedSizeBytes must_== 100
      sizeViolation.failure.actualSizeBytes must_== 1019
      sizeViolation
        .failure
        .expectation must_== "oversized collector payload: cannot split POST requests which are not json expected json value got '������...' (line 1, column 1)"
      sizeViolation.payload.event must_== "CollectorP"
      sizeViolation.processor shouldEqual Processor(TestUtils.appName, TestUtils.appVersion)
    }

    "Reject an oversized POST CollectorPayload which would be oversized even without its body" in {
      val payload = new CollectorPayload()
      val data = Json.obj(
        "schema" := Json.fromString("s"),
        "data" := Json.arr(
          Json.obj("e" := "se", "tv" := "js"),
          Json.obj("e" := "se", "tv" := "js")
        )
      )
      payload.setBody(data.noSpaces.getBytes(StandardCharsets.UTF_8))
      payload.setPath("p" * 1000)
      val actual = splitBatch.splitAndSerializePayload(payload, 1000, 2000L)
      actual.bad.size must_== 1
      val badRow = actual.bad.head
      badRow must beAnInstanceOf[BadRow.SizeViolation]
      val sizeViolation = badRow.asInstanceOf[BadRow.SizeViolation]
      sizeViolation.failure.maximumAllowedSizeBytes must_== 1000
      sizeViolation.failure.actualSizeBytes must_== 1091
      sizeViolation
        .failure
        .expectation must_== "oversized collector payload: cannot split POST requests which are not self-describing DecodingFailure at : Invalid Iglu URI: s, code: INVALID_IGLUURI"
      sizeViolation
        .payload
        .event must_== "CollectorPayload(schema:null, ipAddress:null, timestamp:0, encoding:null, collector:null, path:ppppp"
      sizeViolation.processor shouldEqual Processor(TestUtils.appName, TestUtils.appVersion)
    }

    "Split a CollectorPayload with three large events and four very large events" in {
      val payload = new CollectorPayload()
      val data = Json.obj(
        "schema" := Schemas.SizeViolation.toSchemaUri,
        "data" := Json.arr(
          Json.obj("e" := "se", "tv" := "x" * 600),
          Json.obj("e" := "se", "tv" := "x" * 5),
          Json.obj("e" := "se", "tv" := "x" * 600),
          Json.obj("e" := "se", "tv" := "y" * 1000),
          Json.obj("e" := "se", "tv" := "y" * 1000),
          Json.obj("e" := "se", "tv" := "y" * 1000),
          Json.obj("e" := "se", "tv" := "y" * 1000)
        )
      )
      payload.setBody(data.noSpaces.getBytes(StandardCharsets.UTF_8))
      val actual = splitBatch.splitAndSerializePayload(payload, 1000, 10000)
      actual.bad.size must_== 4
      actual.good.size must_== 2
    }
  }
}
