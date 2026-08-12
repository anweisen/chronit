# Build stage — no local JDK or Gradle required on the host. The wrapper fetches the exact Gradle
# version the repository pins, verifying its checksum.
#
# Pass --build-arg VIA=true to bundle the optional ViaVersion translation layer, which lets the
# client join servers running older protocols.
FROM eclipse-temurin:21-jdk AS build

ARG VIA=false

WORKDIR /build

# Build definitions first, so editing sources does not invalidate the dependency cache layer.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY buildSrc buildSrc
COPY chronit-core/build.gradle.kts chronit-core/
COPY chronit-driver-mcpl/build.gradle.kts chronit-driver-mcpl/
COPY chronit-web/build.gradle.kts chronit-web/
COPY chronit-via/build.gradle.kts chronit-via/
COPY chronit-app/build.gradle.kts chronit-app/

# Git does not always preserve the executable bit on a Windows checkout, and the build context
# carries the mode straight through.
RUN chmod +x gradlew

# Warms the dependency and Gradle-distribution caches. Allowed to fail: it is an optimisation, and
# the real build below reports any genuine resolution problem.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon -Pvia dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true

COPY chronit-core/src chronit-core/src
COPY chronit-driver-mcpl/src chronit-driver-mcpl/src
COPY chronit-web/src chronit-web/src
COPY chronit-via/src chronit-via/src
COPY chronit-app/src chronit-app/src

# Tests need a loopback network for the in-process protocol server; they run in CI rather than
# here, so the image build stays quick and hermetic.
RUN --mount=type=cache,target=/root/.gradle \
    if [ "$VIA" = "true" ]; then VIA_FLAG="-Pvia"; else VIA_FLAG=""; fi && \
    ./gradlew --no-daemon $VIA_FLAG :chronit-app:shadowJar -x test && \
    cp chronit-app/build/libs/chronit.jar /build/chronit.jar


# Runtime stage
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache tzdata tini && \
    addgroup -g 1000 chronit && \
    adduser -D -u 1000 -G chronit chronit && \
    mkdir -p /data/tokens /data/state /data/packs && \
    chown -R chronit:chronit /data

COPY --from=build /build/chronit.jar /app/chronit.jar
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

USER chronit
WORKDIR /data
VOLUME /data

ENV CHRONIT_CONFIG=/data/chronit.yml \
    TZ=UTC \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

# Only meaningful when the web interface is enabled; harmless otherwise.
HEALTHCHECK --interval=60s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8477/healthz || exit 1

ENTRYPOINT ["/sbin/tini", "--", "/app/entrypoint.sh"]
CMD ["daemon"]
