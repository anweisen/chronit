# Scheduling and outcomes

## Daemon or external cron

Both work and both run the same code.

The daemon is the default because a visit is a long-lived stateful session: connect, wait to spawn,
run commands, stay half an hour, leave. That suits a supervised process better than a
fire-and-forget invocation. It also refreshes Microsoft tokens in the background instead of on the
critical path of a run, and it can enforce that one account is never on two servers at once.

To drive it externally instead, `chronit run <job>` does exactly one job and exits with a status
reflecting whether every visit succeeded:

```cron
0 20 * * *  docker run --rm -v chronit-data:/data chronit:latest run nightly
```

## Stopping a run

A running job can be stopped from the dashboard. Cancelling disconnects the live session the way a
client does, rather than dropping the socket and leaving the server to notice on its read timeout,
which is what earns the next visit an "already logged in" kick. It then interrupts the worker, which
spends most of a run blocked on a sleep or a join.

The visit in progress is recorded as *stopped*, every visit after it as *not reached*, and the run
goes into the history as *stopped* rather than as a failure.

## Five outcomes, not two

A run is **complete**, **partial**, **failed**, **stopped** or **skipped**. Each visit inside it is
**ok**, **failed**, **stopped** or **not reached**.

The distinctions carry real information. A job whose second of five servers got kicked is not a job
that could not reach any of them, and neither is a job someone stopped on purpose. Presenting all
three as *failed* is how a word stops being read.

Two consequences are worth calling out. Stopped is never coloured as a fault: it gets its own cool
tone and a pause mark, because an operator who pressed stop does not need to be alarmed about it.
And visits a stopped job never reached are recorded, not left out, so a five-visit job that
stopped after the second reads as `1 of 2, 2 not reached` instead of looking like a two-visit job
whose other three servers were never configured.

The status is written into the history rather than worked out at display time, because "someone
stopped this" cannot be recovered from the visits alone: a job stopped in the gap between two
successful visits would otherwise be indistinguishable from one that finished. History written
before the field existed still loads and derives the closest equivalent.
