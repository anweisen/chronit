# Development

```bash
./gradlew build                                     # compile, unit + protocol integration tests, jar
./gradlew -Pvia build                               # include the translation layer
./gradlew :chronit-app:run --args="validate -c chronit.yml"

java -jar chronit-app/build/libs/chronit.jar validate -c chronit.yml
```

Gradle with the Kotlin DSL. The wrapper pins the exact Gradle version and verifies its checksum, so
nothing needs installing beyond a JDK, and nothing at all if you build the image, which fetches both
in a multi-stage build.

Versions live in one place, `gradle/libs.versions.toml`. Shared build configuration is a convention
plugin in `buildSrc/` rather than a `subprojects { }` block, so each module's build file describes
that module honestly and the configuration cache stays usable.

## Module layout

```
chronit-core/          configuration, scheduling, orchestration, the driver interface
chronit-driver-mcpl/   the Minecraft 26.2 implementation, the only version-aware module
chronit-via/           optional ViaVersion translation, found via ServiceLoader
chronit-web/           optional status and login interface
chronit-app/           command line and executable jar

buildSrc/              shared build conventions
gradle/libs.versions.toml   every dependency version
docker/                Dockerfile and compose, including the Paper test server
config/                the commented example configuration
```

Dependencies use `api` only where a type genuinely appears in a module's public signatures, so a
consumer's compile classpath carries what it is meant to use and no more.

## Testing

The protocol tests run a scripted Minecraft server in-process, built on the same library's server
side with its default handshake listeners switched off so the exchange can be driven precisely. That
makes it possible to assert, without a real server or a network:

- the code of conduct is accepted, and the cookie request answered
- the resource pack sequence is exactly *accepted, downloaded, successfully loaded*, in order, all
  three naming the same pack
- decline mode reports exactly one status, and a failed download reports a failure
- the initial teleport is confirmed and chunk batches acknowledged
- readiness waits for a configured chat pattern and times out cleanly when it never arrives
- commands arrive without a leading slash, and `sendCommand` refuses before the world is loaded
- a menu click names the right slot, echoes the state id it was populated with, and claims no
  prediction; player-inventory slots are offset past the menu; out-of-range slots never reach the
  wire
- a whole configured job runs end to end, with secrets substituted and the history persisted,
  including a command that opens a menu, a click, and a close

A `test` profile in the compose file brings up a real offline-mode Paper server with an enforced
resource pack, for checking against something that is not a test double:

```bash
docker compose -f docker/docker-compose.yml --profile test up -d paper
docker compose -f docker/docker-compose.yml run --rm chronit run --server paper-local --account test --stay 1m
```
