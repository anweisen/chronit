#!/bin/sh
#
# Checks the two supplied files before starting, because each way of getting them wrong otherwise
# produces an error that does not point at the cause.
set -e

fail() {
    echo "chronit: $1" >&2
    shift
    for line in "$@"; do
        echo "  $line" >&2
    done
    exit 1
}

if [ ! -f "$CHRONIT_JAR" ]; then
    jars=$(ls /app/*.jar 2>/dev/null || true)
    fail "no jar at $CHRONIT_JAR" \
        "Put the built jar next to docker-compose.yml and name it chronit-app.jar," \
        "or set CHRONIT_JAR to the name you used." \
        "Jars currently visible: ${jars:-none}"
fi

if [ ! -f "$CHRONIT_CONFIG" ]; then
    fail "no configuration at $CHRONIT_CONFIG" \
        "Put your chronit.yml next to docker-compose.yml."
fi

# The build produces two jars and only one of them runs. Both declare the same Main-Class, so the
# manifest cannot tell them apart — what differs is that chronit.jar bundles its dependencies and
# chronit-app-<version>.jar does not, leaving a few kilobytes of chronit classes that die on the
# first library they touch.
#
# A warning rather than a refusal, on purpose. The marker below is a dependency that happens to be
# needed before anything else; if chronit ever stops using it, this must degrade into a spurious
# warning and not into a container that will not start. Java's own failure follows immediately and
# names the missing class, which this line explains in advance.
if command -v unzip > /dev/null 2>&1 \
        && ! unzip -p "$CHRONIT_JAR" picocli/CommandLine.class > /dev/null 2>&1; then
    echo "chronit: warning — $CHRONIT_JAR does not appear to bundle its dependencies." >&2
    echo "  If startup fails with NoClassDefFoundError, this is the thin jar." >&2
    echo "  Copy chronit-app/build/libs/chronit.jar instead — the large one, tens of megabytes," >&2
    echo "  not chronit-app-<version>.jar." >&2
fi

# The volume. If it is not writable, tokens and run history vanish on every restart, and the first
# sign of it is an interactive login being demanded again next week.
#
# CHRONIT_DATA has to be set to match `stateDir` in the configuration; it is not read from there,
# because parsing YAML in a shell to check one path is a worse trade than an environment variable.
CHRONIT_DATA=${CHRONIT_DATA:-/data}
if [ ! -w "$CHRONIT_DATA" ]; then
    fail "$CHRONIT_DATA is not writable by uid $(id -u)" \
        "This is where tokens and run history live; without it every restart loses them." \
        "If stateDir in chronit.yml is not $CHRONIT_DATA, set CHRONIT_DATA to match and mount" \
        "the volume there. If it is, the volume may be owned by root — fix it with:" \
        "  docker run --rm -v chronit-data:/data alpine chown 1000:1000 /data"
fi

# Unquoted on purpose, so several flags in JAVA_OPTS split into separate arguments.
# shellcheck disable=SC2086
exec java $JAVA_OPTS -jar "$CHRONIT_JAR" "$@"
