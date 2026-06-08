# syntax=docker/dockerfile:1.7

# --- Stage 1: build ---
FROM gradle:9.5-jdk21-alpine AS build
WORKDIR /src

# Copy the whole project and build the fat jar. A "copy build scripts
# first" warm layer can't work for this multi-module build: the root
# settings.gradle.kts eagerly resolves every modules/* projectDir, so it
# fails unless all module dirs are present. The buildx cache mount keeps
# the Gradle dependency cache warm across builds instead.
COPY . .
RUN --mount=type=cache,target=/home/gradle/.gradle \
    ./gradlew --no-daemon :server:shadowJar -x test
RUN cp modules/server/build/libs/silo-*-slim.jar /tmp/silo.jar

# --- Stage 2: runtime ---
FROM eclipse-temurin:21-jre-alpine AS runtime

# OCI labels populated from build args so a plain
# `docker buildx build --build-arg VERSION=… .` carries provenance.
# CI overrides these via docker/metadata-action at push time.
ARG VERSION=dev
ARG VCS_REF=unknown
ARG BUILD_DATE

LABEL org.opencontainers.image.title="Silo" \
      org.opencontainers.image.description="OSS Kotlin/Ktor replacement for the Gradle Remote Build Cache Node." \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.source="https://github.com/chrisjenx/silo" \
      org.opencontainers.image.documentation="https://github.com/chrisjenx/silo#readme" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"

RUN apk add --no-cache wget tini \
 && addgroup -S silo \
 && adduser -S -G silo -h /var/lib/silo silo \
 && mkdir -p /data /etc/silo \
 && chown -R silo:silo /data /etc/silo

WORKDIR /app
COPY --from=build /tmp/silo.jar /app/silo.jar

USER silo

EXPOSE 8080
VOLUME ["/data"]

ENV SILO_PORT=8080 \
    SILO_STORAGE_ROOT=/data \
    JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://127.0.0.1:${SILO_PORT}/health || exit 1

ENTRYPOINT ["/sbin/tini", "--", "sh", "-c", "exec java $JAVA_OPTS -jar /app/silo.jar"]
