package com.snowplowanalytics.snowplow.collector.core

import org.specs2.mutable.Specification

class Rfc6265CookieSpec extends Specification {
  val valid1    = "name=value"
  val valid2    = "name1=value2"
  val bothValid = s"$valid1;$valid2"
  val invalid   = "{\"key\": \"value\"}"

  "Rfc6265Cookie.parse" should {
    "leave a valid cookie as is" in {
      Rfc6265Cookie.parse(valid1) must beSome(valid1)
      Rfc6265Cookie.parse(bothValid) must beSome(bothValid)
    }

    "remove whitespaces" in {
      Rfc6265Cookie.parse(s" $valid1 ") must beSome(valid1)
      Rfc6265Cookie.parse("name = value") must beSome(valid1)
    }

    "remove invalid parts" in {
      Rfc6265Cookie.parse(s"$invalid;$valid1;$valid2") must beSome(bothValid)
      Rfc6265Cookie.parse(s"$valid1;$invalid;$valid2") must beSome(bothValid)
      Rfc6265Cookie.parse(s"$valid1;$valid2;$invalid") must beSome(bothValid)
    }

    "return None if no valid part is left" in {
      Rfc6265Cookie.parse(invalid) must beNone
      Rfc6265Cookie.parse(s";$invalid;") must beNone
      Rfc6265Cookie.parse(";") must beNone
      Rfc6265Cookie.parse(";;") must beNone
    }
  }
}
