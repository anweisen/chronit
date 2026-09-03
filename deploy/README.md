# Deploying chronit to a server

This folder is self-contained. It builds nothing from source: you supply an already-built jar and
your configuration, and everything else is here.

For the setup that builds chronit from source instead, see [`../docker`](../docker).

## What you need on the server

Docker with the Compose plugin. Nothing else — no JDK, no Gradle, no source tree.

## Setting it up

Build the jar on your own machine:

```bash
./gradlew :chronit-app:shadowJar
```

That produces `chronit-app/build/libs/chronit.jar`. **Take that one** — the same folder also holds
`chronit-app-<version>.jar`, which is a few kilobytes of chronit classes with none of its
dependencies and will not start. Both declare the same main class, so the names are the only thing
telling them apart; if you copy the wrong one the container warns before Java fails, so the reason
is in the log rather than left to be guessed from a `NoClassDefFoundError`.

Copy this folder to the server and put two files in it:

```
deploy/
├── chronit-app.jar     ← chronit-app/build/libs/chronit.jar, renamed
├── chronit.yml         ← your configuration
├── docker-compose.yml
├── Dockerfile
├── entrypoint.sh
└── .env                ← optional, copied from .env.example
```

Then authorise each Microsoft account once, and start it:

```bash
docker compose run --rm chronit login main
```

```bash
docker compose up -d
```

The login prints a short code and a link. Open the link on any device, enter the code, and the
container finishes on its own. It writes the session into the `chronit-data` volume, which is why
this has to happen before `up -d` and why it only has to happen once.

Check what it thinks of your accounts:

```bash
docker compose run --rm chronit accounts --refresh
```

## What persists, and what does not

Only the **`chronit-data` volume** holds anything worth keeping: the Microsoft sessions, the run
history, and the resource pack cache. This folder is mounted read-only and chronit never writes to
it.

That means you can replace the jar or edit `chronit.yml` freely, and it means the volume is the one
thing to back up:

```bash
docker run --rm -v chronit-data:/data -v "$PWD:/backup" alpine \
  tar czf /backup/chronit-data.tar.gz -C /data .
```

`docker compose down` keeps the volume. `docker compose down -v` deletes it, and with it every
stored session — you would have to authorise each account from a browser again.

## Updating the jar

```bash
docker compose up -d --force-recreate
```

Replace `chronit-app.jar` first. A plain `restart` is not enough: it reuses the running container,
which is still holding the old file open.

Changing `chronit.yml` needs the same command, since the configuration is read once at startup.

## The dashboard, through Traefik

Published at `https://chronit.anweisen.net` by the labels in `docker-compose.yml`. Three things
have to line up, and each fails differently.

**1. chronit must listen on the container's network interface.** The default `web.bind: 127.0.0.1`
means the container's *own* loopback, which Traefik cannot reach from another container — you get a
502 that looks like chronit is down. In `chronit.yml`:

```yaml
web:
  enabled: true
  bind: 0.0.0.0             # the container's network interface, not the server's
  port: 8477
  token: ${CHRONIT_WEB_TOKEN}
```

chronit refuses to start with a non-loopback bind and no token. That is deliberate, and it matters
more here than usual: the dashboard can start a Microsoft device-code login and cancel running
jobs, and Traefik has just put it on the public internet. Set `CHRONIT_WEB_TOKEN` in `.env` —
Compose fails fast with that message if you forget. A browser posts it once and gets an HttpOnly,
`SameSite=Strict` cookie; scripts can send `Authorization: Bearer <token>` instead.

**2. Traefik and chronit must share a Docker network.** Set `TRAEFIK_NETWORK` in `.env` to the
network Traefik is already on — Compose will not create it:

```bash
docker network ls
```

**3. The `cloudflare` certificate resolver must exist in Traefik's static configuration.** The
label only refers to it by name. Nothing here defines a resolver.

Nothing extra is needed for the live updates. The dashboard is pushed to over a long-lived
`text/event-stream` on `/events`, and Traefik streams responses through rather than buffering them,
so it works as configured. In front of **nginx** it would not: nginx buffers proxied responses by
default, which holds every event until its buffer fills — indistinguishable from a dead connection
on a stream of a few hundred bytes a minute. chronit sends `X-Accel-Buffering: no` on the stream,
which nginx honours; if you put something else in front, check that it does not buffer and that its
idle timeout is above the twenty-second heartbeat.

Then:

```bash
docker compose up -d
```

```bash
docker compose logs -f
```

### What the labels do

They are the Docker-provider equivalent of a dynamic file config, plus the three things a file
config never needs:

| Label | Why |
| --- | --- |
| `traefik.enable=true` | Traefik normally runs with `exposedByDefault=false`. Without this the container is invisible — no route, no error, nothing in the log. |
| `traefik.docker.network` | Which network to reach the container on. Needed the moment a container is on more than one; otherwise Traefik may pick an address it cannot route to. |
| `...loadbalancer.server.port=8477` | Replaces `servers[].url`. Traefik resolves the container's address itself, but this image declares no `EXPOSE`, so the port has to be stated. |
| `...routers.chronit.tls=true` | In a file config, nesting a `tls:` key *is* the switch. In labels the cert resolver alone does not turn TLS on. |

`passhostheader` and `tls.domains[0].main` mirror the file config; both are already the default or
derived from the `Host` rule, and are kept so the two forms read the same.

### Without Traefik

Delete the `labels:` and `networks:` blocks from the service, delete the top-level `networks:`
block, uncomment `ports:`, and keep `web.bind: 127.0.0.1`. `CHRONIT_WEB_TOKEN` can then be dropped
from `.env` and from the `environment:` block, along with the `token:` line in `chronit.yml` — a
loopback bind does not require one. If you also set `web.enabled: false`, delete the `healthcheck:`
block, or the container is reported unhealthy forever for want of something to answer it.

Then reach it over SSH:

```bash
ssh -N -L 8477:127.0.0.1:8477 you@your-server
```

## Day to day

```bash
docker compose logs -f
```

```bash
docker compose run --rm chronit accounts
```

```bash
docker compose run --rm chronit validate
```

`accounts` exits non-zero when a login is genuinely due, so it works as a monitoring check. It
should almost never come to that: the daemon refreshes every session on startup and every six
hours, and each refresh restarts the 90-day window. A login is only needed if the container is off
for three months, the account password changes, or access is revoked.

`run --rm` starts a second container against the same volume. That is safe for `accounts`,
`validate` and `login`, none of which join a server. Do not run a `job` that way while the daemon
is up — the same account would be logged in twice and the two sessions would kick each other.

## Encrypting the stored session

Set `CHRONIT_SECRET_KEY` in `.env` and the token file is encrypted at rest with AES-GCM:

```bash
openssl rand -base64 32
```

Back the key up somewhere other than the server. If it changes, the existing token file cannot be
read — chronit reports that as an error rather than as a missing session, so it will not overwrite
a session that was intact, but recovering means deleting the token file and authorising again.

## When something is wrong

The container checks before starting that both files are present and that the volume is writable,
and refuses to start naming whichever is wrong. If you change `stateDir` in `chronit.yml`, change
`CHRONIT_DATA` in `docker-compose.yml` to match and mount the volume there — otherwise the check
passes while chronit writes its sessions somewhere that is not persisted, and the first sign is a
login being demanded again after a restart. Beyond that:

```bash
docker compose logs --tail=50
```

An account stuck on `NEEDS_LOGIN` after `login` succeeded usually means the login ran without the
volume attached. `docker compose run` uses it; a bare `docker run` of the image does not.
