FROM eclipse-temurin:11-jre-focal as stage0
WORKDIR /opt/snowplow
COPY opt /opt
USER root
RUN ["chmod", "-R", "u=rX,g=rX", "/opt/snowplow"]
RUN ["chmod", "u+x,g+x", "/opt/snowplow/bin/snowplow-stream-collector"]
RUN ["chmod", "u+x,g+x", "/opt/snowplow/bin/kinesis-collector"]

FROM eclipse-temurin:11-jre-focal
LABEL MAINTAINER="Snowplow Analytics Ltd. <support@snowplowanalytics.com>"
WORKDIR /opt/snowplow
COPY --from=stage0 --chown=daemon:root /opt/snowplow /opt/snowplow
USER daemon
ENTRYPOINT ["/opt/snowplow/bin/snowplow-stream-collector"]
CMD []
