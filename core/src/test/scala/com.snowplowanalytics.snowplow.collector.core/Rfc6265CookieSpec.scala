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

    "strip the quotes wrapping a value" in {
      Rfc6265Cookie.parse("name=\"value\"") must beSome(valid1)
      Rfc6265Cookie.parse(s"""name="value";name1="value2"""") must beSome(bothValid)
      Rfc6265Cookie.parse(s"""name="value";$valid2""") must beSome(bothValid)
      Rfc6265Cookie.parse(s"""$valid1;name1="value2"""") must beSome(bothValid)
    }

    "strip the quotes wrapping a base64 value, as reported in CSTMR-2167" in {
      val b64 = "eyJmb28iOiJiYXIifQ=="
      Rfc6265Cookie.parse(s"""RF="$b64"""") must beSome(s"RF=$b64")
    }

    "keep a quoted value that is empty or holds only cookie-octets" in {
      Rfc6265Cookie.parse("name=\"\"") must beSome("name=")
      Rfc6265Cookie.parse("name=\"a+b/c=\"") must beSome("name=a+b/c=")
    }

    "reject a quote that is not wrapping the whole value" in {
      Rfc6265Cookie.parse("name=\"value") must beNone
      Rfc6265Cookie.parse("name=value\"") must beNone
      Rfc6265Cookie.parse("name=va\"lue") must beNone
      // A valid quoted value cannot hold a DQUOTE of its own, so this stays invalid
      Rfc6265Cookie.parse("name=\"va\"lue\"") must beNone
      Rfc6265Cookie.parse("name=\"") must beNone
    }

    "reject a value containing backslashes or improperly placed quotes" in {
      Rfc6265Cookie.parse("""AS_JSON={\"Key\":\"Value\"}""") must beNone
      Rfc6265Cookie.parse("""AS_JSON="{\"Key\":\"Value\"}"""") must beNone
      Rfc6265Cookie.parse(s"""AS_JSON={\"Key\":\"Value\"};$valid1""") must beSome(valid1)
    }
  }
}
