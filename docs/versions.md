# Minecraft versions

The client speaks one protocol version natively: 26.2. That comes from the protocol library, which
ships a single packet codec whose packet classes encode that version's field layouts, so remapping
packet ids alone could not reach another version.

In practice this matters less than it sounds, because most public servers run ViaVersion
*server-side* and accept far newer clients than they advertise. So the default:

```yaml
protocol: auto
```

connects natively first, and only if the server rejects the version does it ping, work out what the
server actually runs, and retry through the translation layer. The successful choice is remembered
per server, so the detour happens once instead of on every run.

A status ping reports what a server *is*, not the range of client versions it will accept, which is
why `auto` tries natively first rather than trusting the ping.

## Reaching older servers

Build with the optional ViaVersion module:

```bash
docker compose -f docker/docker-compose.yml build --build-arg VIA=true
# or, locally:
./gradlew -Pvia build
```

That enables `protocol: 1.20.4` and numeric protocol ids other than 776, and gives `auto` something
to fall back to. Without it, those configurations fail with a message telling you so instead of
failing obscurely at connect time.

The module really is optional. `chronit-core` has no compile-time reference to it and finds it
through `ServiceLoader`, so deleting `chronit-via/` leaves a working 26.2-only application.

## Targeting a different version natively

Change two entries in `gradle/libs.versions.toml` and rebuild:

```toml
[versions]
minecraft = "26.2"
mcpl = "26.2-20260809.160751-16"
```

Nothing outside `chronit-driver-mcpl` needs to change. Everything version-specific sits behind the
`MinecraftClientDriver` interface, which is about a dozen methods dealing only in plain JDK types.
A driver built on a different library, or a hand-written codec, can replace it without touching
configuration, scheduling or the command runner.
