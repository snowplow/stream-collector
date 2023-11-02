/**
  * Copyright (c) 2013-present Snowplow Analytics Ltd.
  * All rights reserved.
  *
  * This program is licensed to you under the Snowplow Community License Version 1.0,
  * and you may not use this file except in compliance with the Snowplow Community License Version 1.0.
  * You may obtain a copy of the Snowplow Community License Version 1.0 at https://docs.snowplow.io/community-license-1.0
  */
package com.snowplowanalytics.snowplow.collector.core

import java.nio.file.{Files, Path}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import io.circe.Decoder
import io.circe.config.syntax.CirceConfigOps
import cats.implicits._
import cats.data.EitherT
import cats.effect.{ExitCode, Sync}

import scala.jdk.CollectionConverters._

object ConfigParser {

  implicit private def logger[F[_]: Sync]: Logger[F] = Slf4jLogger.getLogger[F]

  def fromPath[F[_]: Sync, SinkConfig: Decoder](
    configPath: Option[Path]
  ): EitherT[F, ExitCode, Config[SinkConfig]] = {
    val eitherT = configPath match {
      case Some(path) =>
        for {
          text   <- EitherT(readTextFrom[F](path))
          hocon  <- EitherT.fromEither[F](hoconFromString(text))
          result <- EitherT.fromEither[F](resolve[Config[SinkConfig]](hocon))
        } yield result
      case None =>
        EitherT.fromEither[F](
          for {
            config <- Either
              .catchNonFatal(namespaced(ConfigFactory.load()))
              .leftMap(e => s"Error loading the configuration (without config file): ${e.getMessage}")
            parsed <- config.as[Config[SinkConfig]].leftMap(_.show)
          } yield parsed
        )
    }

    eitherT.leftSemiflatMap { str =>
      Logger[F].error(str).as(ExitCode.Error)
    }
  }

  private def readTextFrom[F[_]: Sync](path: Path): F[Either[String, String]] =
    Sync[F].blocking {
      Either
        .catchNonFatal(Files.readAllLines(path).asScala.mkString("\n"))
        .leftMap(e => s"Error reading ${path.toAbsolutePath} file from filesystem: ${e.getMessage}")
    }

  private def hoconFromString(str: String): Either[String, TypesafeConfig] =
    Either.catchNonFatal(ConfigFactory.parseString(str)).leftMap(_.getMessage)

  private def resolve[A: Decoder](hocon: TypesafeConfig): Either[String, A] = {
    val either = for {
      resolved <- Either.catchNonFatal(hocon.resolve()).leftMap(_.getMessage)
      resolved <- Either.catchNonFatal(loadAll(resolved)).leftMap(_.getMessage)
      parsed   <- resolved.as[A].leftMap(_.show)
    } yield parsed
    either.leftMap(e => s"Cannot resolve config: $e")
  }

  private def loadAll(config: TypesafeConfig): TypesafeConfig =
    namespaced(ConfigFactory.load(namespaced(config.withFallback(namespaced(ConfigFactory.load())))))

  private def namespaced(config: TypesafeConfig): TypesafeConfig = {
    val namespace = "collector"
    if (config.hasPath(namespace))
      config.getConfig(namespace).withFallback(config.withoutPath(namespace))
    else
      config
  }

}
