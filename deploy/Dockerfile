# Runtime-only image. Nothing is compiled here — the jar arrives already built.
#
# This image exists for two reasons that a stock `eclipse-temurin` cannot cover on its own:
# it creates /data owned by a non-root user, which is what a fresh named volume inherits its
# ownership from, and it carries the entrypoint's preflight checks. It is a handful of layers on
# top of the base image and builds in seconds without the source tree.
FROM eclipse-temurin:21-jre-alpine

# tzdata, because a job's `timezone:` is resolved against the system zone database and the JRE
# ships without one on Alpine — every zone would silently fall back to UTC and jobs would fire at
# the wrong hour.
RUN apk add --no-cache tzdata && \
    addgroup -g 1000 chronit && \
    adduser -D -u 1000 -G chronit chronit && \
    mkdir -p /data && \
    chown chronit:chronit /data

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

USER chronit

# The two supplied files are mounted here read-only; everything written goes to /data.
WORKDIR /app
VOLUME /data

ENV CHRONIT_JAR=/app/chronit-app.jar \
    CHRONIT_CONFIG=/app/chronit.yml \
    CHRONIT_DATA=/data \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["daemon"]
