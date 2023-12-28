/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This software is made available by Snowplow Analytics, Ltd.,
  * under the terms of the Snowplow Limited Use License Agreement, Version 1.0
  * located at https://docs.snowplow.io/limited-use-license-1.0
  * BY INSTALLING, DOWNLOADING, ACCESSING, USING OR DISTRIBUTING ANY PORTION
  * OF THE SOFTWARE, YOU AGREE TO THE TERMS OF SUCH LICENSE AGREEMENT.
  */
package com.snowplowanalytics.snowplow.collectors.scalastream
package sinks

import java.net.URI
import java.{lang, util}

import javax.security.auth.callback.Callback
import javax.security.auth.callback.UnsupportedCallbackException
import javax.security.auth.login.AppConfigurationEntry

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.security.auth.AuthenticateCallbackHandler
import org.apache.kafka.common.security.oauthbearer.OAuthBearerToken
import org.apache.kafka.common.security.oauthbearer.OAuthBearerTokenCallback

import com.azure.identity.DefaultAzureCredentialBuilder
import com.azure.core.credential.TokenRequestContext

import com.nimbusds.jwt.JWTParser

class AzureAuthenticationCallbackHandler extends AuthenticateCallbackHandler {

  val credentials = new DefaultAzureCredentialBuilder().build()

  var sbUri: String = ""

  override def configure(
    configs: util.Map[String, _],
    saslMechanism: String,
    jaasConfigEntries: util.List[AppConfigurationEntry]
  ): Unit = {
    val bootstrapServer =
      configs
        .get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)
        .toString
        .replaceAll("\\[|\\]", "")
        .split(",")
        .toList
        .headOption match {
        case Some(s) => s
        case None    => throw new Exception("Empty bootstrap servers list")
      }
    val uri = URI.create("https://" + bootstrapServer)
    this.sbUri = uri.getScheme + "://" + uri.getHost
  }

  override def handle(callbacks: Array[Callback]): Unit =
    callbacks.foreach {
      case callback: OAuthBearerTokenCallback =>
        val token = getOAuthBearerToken()
        callback.token(token)
      case callback => throw new UnsupportedCallbackException(callback)
    }

  def getOAuthBearerToken(): OAuthBearerToken = {
    val reqContext = new TokenRequestContext()
    reqContext.addScopes(sbUri)
    val accessToken = credentials.getTokenSync(reqContext).getToken
    val jwt         = JWTParser.parse(accessToken)
    val claims      = jwt.getJWTClaimsSet

    new OAuthBearerToken {
      override def value(): String = accessToken

      override def lifetimeMs(): Long = claims.getExpirationTime.getTime

      override def scope(): util.Set[String] = null

      override def principalName(): String = null

      override def startTimeMs(): lang.Long = null
    }
  }

  override def close(): Unit = ()
}
