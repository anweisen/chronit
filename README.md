# chronit

Logs a Minecraft account into a list of Java Edition servers on a schedule, gets fully into the
world, runs a configured sequence of commands, stays for a set duration, then moves on to the next
server.

Targets **Minecraft 26.2 (protocol 776)**. Other versions are reachable through an optional
translation layer.

```yaml
jobs:
  - id: nightly
    cron: "0 20 * * *"
    timezone: Europe/Berlin
    visits:
      - server: survival
        account: main
        stayFor: 30m
        onReady:
          - command: "login {{secrets.survival_password}}"
            waitFor: { chat: "(?i)successfully logged in", timeout: 10s }
          - command: "daily claim"
```

---

## Quick start

```bash
cp config/chronit.example.yml ./chronit.yml   # then edit it
docker compose -f docker/docker-compose.yml up -d --build
```

Authorise the Microsoft account once — the code is shown in the log, or on the web interface if you
enable it:

```bash
docker compose -f docker/docker-compose.yml run --rm chronit login main
```

Check what the schedule will actually do before leaving it unattended:

```bash
docker compose -f docker/docker-compose.yml run --rm chronit validate
```

## Commands

| Command | Purpose |
| --- | --- |
| `chronit daemon` | Run the built-in scheduler. The container's default command. |
| `chronit run <job>` | Run one job and exit. For system cron, systemd timers, Kubernetes CronJobs. |
| `chronit run --server <id> --account <id> --stay 10m` | One-off visit, useful for testing. |
| `chronit login <account>` | Interactive Microsoft device code login. |
| `chronit accounts` | Which accounts are ready, and which need a login. Non-zero exit if any need one. |
| `chronit ping <server\|host:port>` | Server list ping. |
| `chronit validate` | Check the configuration and print the resolved schedule. |
| `chronit --version` | Version, protocol, and whether translation is installed. |

## Scheduling: daemon or external cron

Both work, and they run identical code.

The **daemon** is the default because a visit is a long-lived stateful session — connect, wait to
spawn, run commands, stay half an hour, leave — which suits a supervised process better than a
fire-and-forget invocation. It also refreshes Microsoft tokens quietly in the background instead of
on the critical path of a run, and can enforce that one account is never on two servers at once.

If you would rather drive it externally, `chronit run <job>` does exactly one job and exits with a
status reflecting whether every visit succeeded:

```cron
0 20 * * *  docker run --rm -v chronit-data:/data chronit:latest run nightly
```

---

## What it takes to actually get into a world

Most of the work is not "connect and send a command" — it is satisfying everything a modern server
demands before it will let a client in. Each of the following either terminates the connection or
stalls the join if skipped, and this handles all of them:

- **Code of conduct** — new in Minecraft 26.x. The server can present one during the configuration
  phase and disconnects a client that does not accept it. The text is written to the log, since
  agreeing to something unread is worth recording.
- **Resource packs** — a server with `require-resource-pack=true` disconnects on a decline. A real
  client reports *accepted*, then *downloaded*, then *successfully loaded*, separated by however long
  the download and reload took. See below.
- **Cookie requests** — added in 1.20.5 and used by proxy networks. An unanswered request stalls the
  join indefinitely.
- **Client settings and brand** — sent on entering configuration and again after joining, as vanilla
  does.
- **Teleport confirmation** — the first confirmed teleport is the dependable "in the world" signal.
  Reaching the play state only means configuration finished.
- **Chunk batch acknowledgement** — without it the server keeps its chunk throttle at the starting
  value and chunks trickle in, so a readiness condition waiting on them never completes.
- **Chat acknowledgements** — since 1.19.1 the server counts the signed messages it has sent and
  expects them acknowledged. Neglect it and the connection is closed over a chat validation failure
  within a minute of joining on a busy server, whether or not the bot ever says anything.
- **Client tick cadence** — the tick-end packet every tick and a state report every second, which is
  what a stationary vanilla client sends.

### Resource packs

```yaml
resourcePack:
  mode: DOWNLOAD    # FAKE | DOWNLOAD | DECLINE
```

- **`FAKE`** never fetches anything and reports the full success sequence after plausible,
  jittered delays. Cheapest, and works everywhere.
- **`DOWNLOAD`** actually fetches the pack, verifies the SHA-1 the server supplied, and reports the
  real elapsed time. Slower and uses bandwidth, but a plugin comparing pack size against how long the
  client took sees consistent numbers. Cached by hash, so each pack is fetched once.
- **`DECLINE`** refuses — expect a kick when the pack is required. Useful for confirming a pack is
  what is breaking a join.

A hash mismatch is logged and accepted by default, because operators updating a pack without
updating the hash is common and failing is what gets you kicked; `strict: true` reports a failure
instead.

### Knowing when commands can be sent

Commands sent before the world is loaded are silently dropped, and many networks park arriving
players in a lobby or authentication world first. The readiness gate is therefore explicit:

```yaml
readyWhen:
  spawn: true                    # join packet + confirmed initial teleport (default)
  minChunks: 0
  chat: "(?i)welcome to"         # for servers that announce the handover
  settle: 2s
  timeout: 60s
```

Readiness additionally waits for any resource pack still mid-sequence. `sendCommand` throws rather
than silently doing nothing if called early.

### Command sequences

```yaml
onReady:
  - command: "login {{secrets.password}}"     # no leading slash
    waitFor:
      chat: "(?i)successfully logged in"
      timeout: 10s
      onTimeout: CONTINUE                     # CONTINUE | STOP | FAIL
    delayAfter: 1s
  - command: "warp daily"
    delayAfter: 5s
  - chat: "gg"
  - wait: 3s
onLeave:
  - command: "logout"
```

`waitFor` is preferable to a fixed delay: it continues the moment the server answers and still holds
on when the server is slow. The waiter is registered before the command is sent, so a reply arriving
within a millisecond is not missed.

Commands go out on the unsigned command packet, which carries no signature or acknowledgement
fields — which is why command sequences work even on servers with `enforce-secure-profile=true`.
Plain `chat:` messages are signed when the account has a usable certificate.

`stayFor` is measured from reaching the world, so it is the total time present on the server rather
than time in addition to however long the commands took.

---

## Versions

The client speaks **one** protocol version natively: 26.2. That is a property of the protocol
library, which ships a single packet codec whose packet classes encode that version's field layouts
— remapping packet ids alone could not reach another version.

In practice this matters less than it sounds, because most public servers run ViaVersion
*server-side* and accept far newer clients than they advertise. So:

```yaml
protocol: auto      # default
```

connects natively first, and only if the server rejects the version does it ping, work out what the
server actually runs, and retry through the translation layer. The successful choice is remembered
per server, so the detour happens once rather than on every run.

Note that a status ping reports what a server **is**, not the range of client versions it will
accept — which is exactly why `auto` tries natively first rather than trusting the ping.

### Reaching older servers

Build with the optional ViaVersion module:

```bash
docker compose -f docker/docker-compose.yml build --build-arg VIA=true
# or, locally:
./gradlew -Pvia build
```

That enables `protocol: 1.20.4` and numeric protocol ids other than 776, and gives `auto` something
to fall back to. Without it, those configurations fail with a message telling you so rather than
failing obscurely at connect time.

The module is genuinely optional: `chronit-core` has no compile-time reference to it and finds it
through `ServiceLoader`. Deleting `chronit-via/` leaves a working 26.2-only application.

### Targeting a different Minecraft version natively

Change two entries in `gradle/libs.versions.toml` and rebuild:

```toml
[versions]
minecraft = "26.2"
mcpl = "26.2-20260809.160751-16"
```

Nothing outside `chronit-driver-mcpl` needs to change. Everything version-specific lives behind the
`MinecraftClientDriver` interface, which is about a dozen methods and deals only in plain JDK types
— so a driver built on a different library, or a hand-written codec, can replace it without touching
configuration, scheduling or the command runner.

---

## Accounts

Microsoft authentication uses the **device code flow**: `chronit login main` prints a short code and
a link, you enter it on any device with a browser, and the container polls until it completes. There
is no redirect URI and no public HTTPS endpoint involved, which is the only workable arrangement for
something running headless.

The stored session is refreshed automatically. A Minecraft access token lasts about a day and the
underlying refresh token about ninety, so an interactive login is needed roughly once a quarter —
`chronit accounts` exits non-zero when one is due, and the daemon says so at startup.

Set `CHRONIT_SECRET_KEY` to encrypt the stored session at rest (AES-GCM). Keep the value stable;
changing it makes the existing token file unreadable and forces a fresh login.

`auth: OFFLINE` accounts need no login and derive their UUID exactly as a vanilla server does for
`online-mode=false`, so the bot keeps a stable identity across visits. They only work against
offline-mode servers, which makes them right for local testing.

## Secrets

Server login passwords should not sit in the main configuration:

```yaml
secretsFile: /data/secrets.yml    # name: value pairs, referenced as {{secrets.name}}
```

Values may also come from the environment as `CHRONIT_SECRET_<NAME>`, which wins over the file. Any
string in the configuration can also use `${ENV_VAR}` or `${ENV_VAR:-fallback}`.

Every resolved secret is registered with the log redactor and masked wherever it would otherwise be
rendered — including inside exception messages, which is where this sort of thing usually leaks.

## Web interface

Off by default. Enable it for a dashboard showing the schedule, account state, and recent runs, with
a *Run now* button and a *Log in* button that walks through the device code flow in the browser —
considerably nicer than catching a fifteen-minute code out of `docker logs -f` every ninety days.

```yaml
web:
  enabled: true
  bind: 127.0.0.1       # a token is required when this is not loopback
  port: 8477
  token: ${CHRONIT_WEB_TOKEN}
```

It is a handful of server-rendered pages on the JDK's own HTTP server — no framework, no JavaScript
build step. `/healthz` is unauthenticated and reveals nothing; everything else requires the token
when one is set.

---

## Development

```bash
./gradlew build                                     # compile, unit + protocol integration tests, jar
./gradlew -Pvia build                               # include the translation layer
./gradlew :chronit-app:run --args="validate -c chronit.yml"

java -jar chronit-app/build/libs/chronit.jar validate -c chronit.yml
```

Gradle with the Kotlin DSL. The wrapper pins the exact Gradle version and verifies its checksum, so
nothing needs installing beyond a JDK — and nothing at all if you build the image, which fetches
both in a multi-stage build.

Versions live in one place, `gradle/libs.versions.toml`. Shared build configuration is a convention
plugin in `buildSrc/` rather than a `subprojects { }` block, so each module's build file describes
that module honestly and the configuration cache stays usable.

### Testing

The protocol tests run a **scripted Minecraft server in-process**, built on the same library's
server side with its default handshake listeners switched off so the exchange can be driven
precisely. That makes it possible to assert, without a real server or a network:

- the code of conduct is accepted, and the cookie request answered
- the resource pack sequence is exactly *accepted → downloaded → successfully loaded*, in order,
  all three naming the same pack
- decline mode reports exactly one status, and a failed download reports a failure
- the initial teleport is confirmed and chunk batches acknowledged
- readiness waits for a configured chat pattern and times out cleanly when it never arrives
- commands arrive without a leading slash, and `sendCommand` refuses before the world is loaded
- a whole configured job runs end to end, with secrets substituted and the history persisted

A `test` profile in the compose file brings up a real offline-mode Paper server with an enforced
resource pack, for checking against something that is not a test double:

```bash
docker compose -f docker/docker-compose.yml --profile test up -d paper
docker compose -f docker/docker-compose.yml run --rm chronit run --server paper-local --account test --stay 1m
```

### Layout

```
chronit-core/          configuration, scheduling, orchestration, the driver interface
chronit-driver-mcpl/   the Minecraft 26.2 implementation — the only version-aware module
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

---

## Limitations worth knowing

- **The client stands still.** It sends what a stationary vanilla client sends. It does not wander
  around to defeat AFK kicks; a server that kicks idle players will kick it.
- **Signed chat is best effort.** Commands are unaffected, but if a server rejects signed plain chat,
  set `secureChat: OFF` and use commands instead.
- **The ViaVersion path is not covered by the automated tests**, which exercise the native 26.2 path
  against the in-process server. The integration is written against the documented ViaLoader API but
  has not been run against a real legacy server here.
- **One account, one server at a time.** Logging in again invalidates the earlier session, so visits
  sharing an account are serialised. That is enforced, not merely documented.

## A note on server rules

Many servers' rules prohibit unattended or AFK clients. Nothing here evades detection — it implements
the same handshakes a vanilla client performs, and identifies itself with a configurable brand. Check
the rules of the servers you point it at.
