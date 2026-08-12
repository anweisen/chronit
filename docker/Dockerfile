# Build stage — no local Maven or JDK required on the host.
#
# Pass --build-arg VIA=true to bundle the optional ViaVersion translation layer, which lets the
# 26.2 client join servers running older protocols.
FROM maven:3.9-eclipse-temurin-21 AS build

ARG VIA=false

WORKDIR /build

# Resolve dependencies against the POMs alone first, so editing sources does not re-download
# the world on every rebuild.
COPY pom.xml .
COPY chronit-core/pom.xml chronit-core/
COPY chronit-driver-mcpl/pom.xml chronit-driver-mcpl/
COPY chronit-web/pom.xml chronit-web/
COPY chronit-via/pom.xml chronit-via/
COPY chronit-app/pom.xml chronit-app/
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -q dependency:go-offline -DskipTests || true

COPY chronit-core/src chronit-core/src
COPY chronit-driver-mcpl/src chronit-driver-mcpl/src
COPY chronit-web/src chronit-web/src
COPY chronit-via/src chronit-via/src
COPY chronit-app/src chronit-app/src

RUN --mount=type=cache,target=/root/.m2/repository \
    if [ "$VIA" = "true" ]; then PROFILES="-Pvia"; else PROFILES=""; fi && \
    mvn -B -DskipTests $PROFILES package && \
    cp chronit-app/target/chronit.jar /build/chronit.jar


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
