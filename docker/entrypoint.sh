#!/bin/sh
set -e

# JAVA_OPTS is intentionally unquoted so multiple flags split into separate arguments.
# shellcheck disable=SC2086
exec java $JAVA_OPTS -jar /app/chronit.jar "$@"
