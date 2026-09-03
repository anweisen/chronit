# Configuring a visit

`config/chronit.example.yml` is the commented reference with every option in it. This page covers
the parts that need more than a comment.

## Resource packs

```yaml
resourcePack:
  mode: DOWNLOAD    # FAKE | DOWNLOAD | DECLINE
```

`FAKE` fetches nothing and reports the full success sequence after plausible, jittered delays. It
is the cheapest option and works everywhere.

`DOWNLOAD` really fetches the pack, verifies the SHA-1 the server supplied, and reports the elapsed
time it actually took. It is slower and uses bandwidth, but a plugin comparing pack size against
client download time sees consistent numbers. Packs are cached by hash, so each one is fetched once.

`DECLINE` refuses, so expect a kick when the pack is required. Useful for confirming that a pack is
what breaks a join.

A hash mismatch is logged and then accepted, because operators updating a pack without updating the
hash is common and refusing is what gets you kicked. Set `strict: true` to report a failure instead.

## Knowing when commands can be sent

Commands sent before the world is loaded are silently dropped, and many networks park arriving
players in a lobby or an authentication world first. So the readiness gate is explicit:

```yaml
readyWhen:
  spawn: true                    # join packet + confirmed initial teleport (default)
  minChunks: 0
  chat: "(?i)welcome to"         # for servers that announce the handover
  settle: 2s
  timeout: 60s
```

Readiness also waits for any resource pack still mid-sequence. `sendCommand` throws when it is
called early instead of quietly doing nothing.

## Command sequences

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

Prefer `waitFor` over a fixed delay: it continues the moment the server answers and still holds on
when the server is slow. The waiter is registered before the command goes out, so a reply arriving
within a millisecond is not missed.

`stayFor` is measured from reaching the world, so it is the total time present on the server rather
than time added on top of however long the commands took.

## Clicking plugin menus

A lot of rewards live behind an inventory GUI: a command opens a menu, and the thing you want is a
slot in it.

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

`slot` counts within the opened menu, the top inventory, starting at 0, so a configuration does not
need to know how large the menu is. `inventory: PLAYER` addresses your own inventory underneath
instead, where 0-26 are the three main rows and 27-35 the hotbar; that mapping needs the menu's
size, which the server only reveals along with the contents. `button` (`LEFT`/`RIGHT`) and `mode`
(`PICKUP`/`SHIFT`/`DROP`) both default to an ordinary left click.

What makes this reliable rather than flaky:

`waitFor.screen` waits for a menu that is open *and* populated. A server sends the window first and
fills it a moment later, and a click in that gap lands on an empty slot and does nothing. The wait
is registered before the command goes out, because menus often open within a millisecond.

The click echoes the menu's current state id, which is how the server detects a client working from
a stale view. chronit tracks it from every content and slot update the server sends.

The click claims no prediction. A real client also tells the server which slots it expects to change
and what ends up on the cursor. Sending nothing predicted leaves the server authoritative, so it
applies the click and resyncs if its own outcome differs. For a plugin menu, which cancels the event
and repaints anyway, that is both correct and the honest option: a real prediction would mean
hashing item data components, and a wrong hash is worse than no claim at all.

Clicking with no menu open, or naming a slot outside it, fails the visit with a message saying so
instead of sending a packet the server will ignore. The menu is closed before disconnecting, as a
real client does.

## Secrets

Server login passwords should not sit in the main configuration:

```yaml
secretsFile: /data/secrets.yml    # name: value pairs, referenced as {{secrets.name}}
```

Values can also come from the environment as `CHRONIT_SECRET_<NAME>`, which wins over the file. Any
string in the configuration can use `${ENV_VAR}` or `${ENV_VAR:-fallback}`.

Every resolved secret is registered with the log redactor and masked wherever it would otherwise be
printed, including inside exception messages, which is where this sort of thing usually leaks.
