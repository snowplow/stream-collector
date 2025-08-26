package com.snowplowanalytics.snowplow.collector.core
package compression

object CompressorTestUtils {

  def verifyFormat(decompressed: Array[Byte], expectedRecords: List[Array[Byte]]): Boolean =
    TestUtils.parseRecords(decompressed) match {
      case Left(_) => false
      case Right(actualRecords) =>
        actualRecords.length == expectedRecords.length &&
          actualRecords.zip(expectedRecords).forall {
            case (actual, expected) =>
              actual.sameElements(expected)
          }
    }
}
