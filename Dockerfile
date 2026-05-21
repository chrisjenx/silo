# syntax=docker/dockerfile:1.7

# --- Stage 1: build ---
FROM gradle:8.10-jdk21-alpine AS build
WORKDIR /src

# Warm dependency cache layer
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties* ./
COPY buildSrc buildSrc 2>/dev/null || true
RUN --mount=type=cache,target=/home/gradle/.gradle ./gradlew --no-daemon dependencies || true

# Copy sources and build
COPY . .
RUN --mount=type=cache,target=/home/gradle/.gradle \
    ./gradlew --no-daemon :server:shadowJar -x test
RUN cp modules/server/build/libs/silo-*-all.jar /tmp/silo.jar

# --- Stage 2: runtime ---
FROM eclipse-temurin:21-jre-alpine AS runtime

LABEL org.opencontainers.image.title="Silo"
LABEL org.opencontainers.image.description="OSS Kotlin/Ktor replacement for the Gradle Remote Build Cache Node."
LABEL org.opencontainers.image.licenses="Apache-2.0"
LABEL org.opencontainers.image.source="https://github.com/chrisjenx/silo"
LABEL org.opencontainers.image.documentation="https://github.com/chrisjenx/silo#readme"

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
