/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
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

import com.microsoft.azure.credentials.MSICredentials

import com.nimbusds.jwt.JWTParser

class AzureAuthenticationCallbackHandler extends AuthenticateCallbackHandler {

  val credentials: MSICredentials = {
    val clientId = sys.env.get("AZURE_CLIENT_ID_FOR_EVENT_HUB").orElse(sys.env.get("AZURE_CLIENT_ID"))
    val creds    = new MSICredentials()
    clientId.map(creds.withClientId).getOrElse(creds)
  }

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
    val accessToken = credentials.getToken(sbUri)
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
