# syntax=docker/dockerfile:1.7

# --- Stage 1: build ---
FROM gradle:9.5-jdk21-alpine AS build
WORKDIR /src

# Warm dependency cache layer. Copy only the wrapper + build scripts so
# this layer is cached unless the build definition itself changes.
COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY buildSrc buildSrc
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
RUN --mount=type=cache,target=/home/gradle/.gradle ./gradlew --no-daemon dependencies || true

# Copy sources and build
COPY . .
RUN --mount=type=cache,target=/home/gradle/.gradle \
    ./gradlew --no-daemon :server:shadowJar -x test
RUN cp modules/server/build/libs/silo-*-all.jar /tmp/silo.jar

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
