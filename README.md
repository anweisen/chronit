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

There are two Docker setups, and they are for different jobs. [`docker/`](docker) builds chronit
from this source tree and is what the commands above use — right for development, and for a server
you are happy to build on. [`deploy/`](deploy/README.md) builds nothing: you copy that one folder
to a server, drop in a jar you built elsewhere and your `chronit.yml`, and start it. Use it when
the server has no source tree, no JDK, and no reason to acquire either.

## Commands

| Command | Purpose |
| --- | --- |
| `chronit daemon` | Run the built-in scheduler. The container's default command. |
| `chronit run <job>` | Run one job and exit. For system cron, systemd timers, Kubernetes CronJobs. |
| `chronit run --server <id> --account <id> --stay 10m` | One-off visit, useful for testing. |
| `chronit login <account>` | Interactive Microsoft device code login. |
| `chronit accounts [--refresh]` | Which accounts are ready, and which need a login. Non-zero exit if any need one. |
| `chronit ping <server\|host:port>` | Server list ping. |
| `chronit validate` | Check the configuration and print the resolved schedule. |
| `chronit --version` | Version, protocol, and whether translation is installed. |

A running job can be stopped from the dashboard. Cancelling disconnects the live session the way a
client does — rather than dropping the socket and leaving the server to notice on its read timeout,
which is what earns the next visit an "already logged in" kick — then interrupts the worker, which
spends most of a run blocked on a sleep or a join. The visit in progress is recorded as *stopped*,
the remaining visits are skipped, and the run still goes into the history.

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

### Clicking plugin menus

A great many rewards live behind an inventory GUI: a command opens a menu, and the thing you want is
a slot in it.

```yaml
onReady:
  - command: "rewards"
    waitFor:
      screen: "(?i)daily rewards"   # matched against the menu title; "" accepts any menu
      timeout: 10s
      onTimeout: FAIL
  - click: { slot: 13 }
    delayAfter: 500ms
  - closeScreen: true
```

`slot` counts within the **opened menu** — the top inventory — starting at 0, so a configuration does
not need to know how large the menu is. `inventory: PLAYER` addresses your own inventory underneath
instead, where 0-26 are the three main rows and 27-35 the hotbar; that mapping needs the menu's size,
which the server only reveals with the contents. `button` (`LEFT`/`RIGHT`) and `mode`
(`PICKUP`/`SHIFT`/`DROP`) both default to an ordinary left click.

Three details make this reliable rather than flaky:

- **`waitFor.screen` waits for a menu that is open *and populated*.** A server sends the window
  first and fills it a moment later; a click in that gap lands on an empty slot and does nothing.
  The wait is registered before the command goes out, because menus often open within a millisecond.
- **The click echoes the menu's current state id**, which is how the server detects a client working
  from a stale view. It is tracked from every content and slot update the server sends.
- **The click claims no prediction.** A real client also tells the server which slots it expects to
  change and what ends up on the cursor; sending nothing predicted leaves the server authoritative,
  so it applies the click and resyncs if its own outcome differs. For a plugin menu — which cancels
  the event and repaints anyway — that is both correct and the honest option, since a real prediction
  would mean hashing item data components and a wrong hash is worse than no claim at all.

Clicking with no menu open, or naming a slot outside it, fails the visit with a message saying so
rather than sending a packet the server will ignore. The menu is closed automatically before
disconnecting, as a real client does.

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

### Why you should almost never have to log in again

Three token lifetimes are involved, and conflating them is where the folklore about re-authorising
every quarter comes from:

| Token | Lasts | What it is for |
| --- | --- | --- |
| Minecraft access token | ~24 hours | what the server checks when you join |
| Microsoft access token | ~1 hour | mints the Minecraft one |
| Microsoft refresh token | 90 days | mints both, and **replaces itself every time it is used** |

That last row is the important one. Microsoft issues a new refresh token on every refresh, and each
one starts a fresh ninety days. The ninety days is therefore a limit on being *left alone*, not a
countdown to an unavoidable login. A daemon that touches its sessions on a schedule never reaches
it, and the only things that genuinely end a session are a password change, an explicit revocation,
or leaving the process off for three months.

So chronit keeps them warm the way a desktop launcher does — Prism Launcher, for instance, walks its
account list on a timer and renews anything approaching expiry rather than waiting for the game to be
started. The daemon refreshes every Microsoft account at startup and every six hours after that:

```yaml
auth:
  refreshOnStart: true    # renew at startup, so a session that went stale while the process
                          # was down is found immediately rather than by the 3am run
  refreshInterval: 6h     # background sweep; 0 refreshes only when a visit needs it
  refreshMargin: 30m      # treat a token as due this long before it expires
```

Refreshing is proactive, not reactive. A token with a minute left on it is no use — the server
revalidates it against Mojang during the join, and a join is not instant — so anything inside the
margin is renewed before it is handed to the driver. The sweep looks one whole interval further
ahead than that (`refreshInterval + refreshMargin`), because a token that would lapse between two
sweeps has to be caught by the earlier one.

`chronit accounts` shows how long each session would survive being left alone; add `--refresh` to
renew them all first, which is the only way to actually find out whether a session still works — the
plain report is read from disk and makes no request. It exits non-zero when a login is genuinely
due, so a monitoring check can catch it, and the daemon says so at startup.

Refresh failures are told apart rather than lumped together: a Microsoft outage is retried and
logged as such, while a revoked or expired session is what asks for `chronit login`. A visit does not
burn its retries on the latter.

Set `CHRONIT_SECRET_KEY` to encrypt the stored session at rest (AES-GCM). Keep the value stable;
changing it makes the existing token file unreadable — which is reported as an error rather than as
a missing session, so a login cannot overwrite a session that was intact all along.

If you set `clientId` to your own Azure registration after having logged in without one, the stored
session is refused rather than silently kept: a refresh token only works for the application that
issued it. Run `chronit login` once and it is yours from then on.

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

Off by default. Enable it for a dashboard showing the schedule, every job's full visit chain,
account state and recent runs, with a *Run now* button and a *Sign in* flow that walks through the
device code in the browser — considerably nicer than catching a fifteen-minute code out of
`docker logs -f` every ninety days.

```yaml
web:
  enabled: true
  bind: 127.0.0.1       # a token is required when this is not loopback
  port: 8477
  token: ${CHRONIT_WEB_TOKEN}
```

Each job card shows what the schedule will actually do: the cron in plain English, the next fire
time, and then every visit — server and address, account, how long it stays, resource pack mode,
readiness conditions, secure chat mode, retry policy, and each configured step in order with its
waits. Command payloads go through the log redactor first, so a `login <password>` step shows as
`/login ***`.

Run history answers the questions actually asked after a failure, in order: did it get into the
world at all and how long that took, how long it stayed, what it managed to run, how many attempts
it took, which protocol it spoke, and what ended it — the outcome named (`version refused`,
`resource pack`, `auth failed`) rather than left as a message to interpret.

### One vocabulary

Every page is assembled from the same two shapes. Secondary information is always a **labelled
value** — a small capitalised label above its value — so `Europe/Berlin` is visibly a timezone and
`10s` visibly a duration, rather than both being anonymous fragments in a run-on line. Anything
genuinely list-like is a row of **chips**. Between them those cover every place a string of
dot-separated text would otherwise appear, and because they are literally the same components, the
same kind of information looks the same on the dashboard, in a run's detail, on the sign-in page and
inside the confirmation dialog.

The confirmation is a native `<dialog>`, so focus trapping, the backdrop and escape-to-dismiss are
the browser's job rather than three more things to reimplement imperfectly.

### Layout and type

The page opens as a short summary and expands into the whole configuration. There is no row of
dashboard tiles: four equal boxes give a next-run time the same weight as an account that has
stopped working, when only one of those needs a person. Instead the routine facts read as one line
of prose, and anything actually waiting on you appears as a banner with the button that fixes it —
rendered only when it applies.

Detail is present but folded. A job's visit chain and the system information sit behind
disclosures, and which ones you left open is remembered, so watching one job does not mean
re-opening it after every refresh.

Typography follows Apple's conventions: San Francisco where it exists and the platform's own UI
face otherwise, a small set of named text styles rather than ad-hoc sizes, tracking that tightens
as type grows and opens up for small capitalised labels, and tabular figures anywhere a number
changes in place — without them a ticking countdown shifts its own width every second.

### How it is built

Server-rendered pages on the JDK's own HTTP server — no framework, no JavaScript build step, and it
works with scripting disabled. Two decisions are worth knowing about:

**Markup comes from a typed builder, not string concatenation.** Everything on these pages
originates outside the process — server names, kick reasons, menu titles, chat — and with
`StringBuilder` a single forgotten escape is an injection hole. In the builder the only way to put
content into a node is `text()`, which escapes, so the mistake is not available to make. Tests fire
`<img src=x onerror=…>` through a kick reason to keep it that way.

**The page does not reload.** A ~400 byte JSON snapshot is polled every six seconds and patches the
few values that change; polling stops entirely while the tab is hidden. Countdowns tick locally from
embedded timestamps, so staying current costs no requests at all. When the history changes, the
*server-rendered* run fragment is fetched and swapped in — so there is still only one description of
what a run looks like. Actions go through `fetch` and answer with a toast rather than a blind
redirect.

The stylesheet and script are served as cacheable assets with a content-hashed URL and an ETag, so a
poll never re-downloads them. Account status is cached briefly, because reading it parses a token
file from disk and the dashboard asks often.

### Access

`/healthz` is unauthenticated and reveals nothing. Everything else needs the token when one is set,
supplied either as `Authorization: Bearer …` for tooling, or through a small sign-in form for a
browser, which exchanges it for an `HttpOnly; SameSite=Strict` cookie. A token is deliberately *not*
accepted as a query parameter — that would put it in browser history, in referrer headers and in any
access log in front of the process.

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
- a menu click names the right slot, echoes the state id it was populated with, and claims no
  prediction; player-inventory slots are offset past the menu; out-of-range slots never reach the wire
- a whole configured job runs end to end, with secrets substituted and the history persisted —
  including a command that opens a menu, a click, and a close

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
- **Menu clicks address slots, not items.** There is no "click the slot containing a diamond" —
  slot indices are stable in the plugin menus this is for, and matching on item identity would mean
  decoding item data components the click path deliberately avoids.

## A note on server rules

Many servers' rules prohibit unattended or AFK clients. Nothing here evades detection — it implements
the same handshakes a vanilla client performs, and identifies itself with a configurable brand. Check
the rules of the servers you point it at.
