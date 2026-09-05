# Web interface

![The chronit dashboard](images/dashboard-home.png)

Off by default. Turn it on for a dashboard showing the schedule, every job's full visit chain, account state and recent
runs, with a *Run now* button and a *Sign in* flow that walks through the device code in the browser. That is
considerably nicer than catching a fifteen-minute code out of
`docker logs -f` every ninety days.

```yaml
web:
  enabled: true
  bind: 127.0.0.1       # a token is required when this is not loopback
  port: 8477
  token: ${CHRONIT_WEB_TOKEN}
```

Each job shows what the schedule will actually do: the cron in plain English, the next fire time, and then every visit
with its server and address, account, stay duration, resource pack mode, readiness conditions, secure chat mode, retry
policy, and each configured step in order with its waits. Command payloads go through the log redactor first, so a
`login <password>` step shows as
`/login ***`.

Run history answers the questions people actually ask after a failure, in order: did it get into the world at all and
how long that took, how long it stayed, what it managed to run, how many attempts it took, which protocol it spoke, and
what ended it. The outcome is named (`version refused`,
`resource pack`, `auth failed`) instead of being left as a message to interpret. The five run outcomes are described
in [Scheduling and outcomes](scheduling.md#five-outcomes-not-two).

## Design

The interface is built from four devices and nothing else. A **rail**, two pixels of colour with a node at its head,
begins every job, account, run and notice, and is what makes the whole page look like one page. A **band** is a region
whose name sits in the left margin instead of inside a container. A **state** is a drawn mark and a word in the colour
of the thing it describes. A **hairline** does all the separating that borders used to.

Nothing is a card and nothing is a filled capsule. A screen of cards is a screen of identical rectangles competing for
the same attention, which leaves the thing that needs attention with nothing to stand out against, and a row of capsules
reads as a row of buttons. Status is carried by the shape of its mark first (a check, a cross, a pause, a dash, an open
ring for anything at rest), so it survives a reader who cannot separate the two hues that matter most, with colour
confirming rather than carrying. The dash means a visit a run never reached, so a disabled job takes the open ring
instead: it was not passed over, it is simply not scheduled.

Every label-and-value pair anywhere on the page, whether it is a job's schedule, a visit's configuration, a run's
per-visit record or the system information, is a row of the same aligned table: the label in a column of one fixed
width, the value against it. So `Europe/Berlin` is visibly a timezone and `10s` visibly a duration, and two unrelated
blocks of facts line up with each other. A grid that reflows into two, three or four columns depending on available
width makes the reader hunt for each label before they can read its value.

The one exception is a collapsed summary, where a table is the wrong shape. There the same pairs sit on one line as a
**meta strip**, in identical type, separated by hairlines. Nothing else anywhere lays out a label and a value.

Run history is drawn as what it is: a single spine down the page with a node per run. Opening a run hangs its visits on
that same line rather than starting a second one beside it, with smaller nodes and their text set a step further in, so
the hierarchy is carried by where the content begins. Inside an expanded run each visit is strictly one thing per line,
so who and how it went, then the record as a table, then the reason. Nothing sits beside anything else, because two
blocks competing for the left edge of a row give the eye two places to start.

The confirmation is a native `<dialog>`, so focus trapping, the backdrop and escape-to-dismiss are the browser's job.

## Layout and motion

The page opens as one headline number, the next fire time or the elapsed clock of whatever is running, and expands into
the whole configuration. There is no row of dashboard tiles: four equal boxes give a next-run time the same weight as an
account that has stopped working, when only one of those needs a person. Anything waiting on you appears with the action
that fixes it, and only when it applies.

Detail is present but folded. A job's visit chain, each run's visits and the system information sit behind disclosures,
and which ones you left open is remembered, so watching one job does not mean re-opening it every time something
changes.

Because the page is pushed to and never reloaded, things arrive while you are looking at them, so nothing appears in a
single frame. Everything state-driven uses one of three devices, which is what stops a job starting from looking
different to an account expiring. A **reveal** opens and closes its own height, so the row settles instead of jumping;
the progress line under a running job is one. A **swap** is two controls sharing one grid cell, so *Run now* and *Stop*
cross-fade in place and the row cannot resize under the pointer at the moment the button beneath it is replaced. An
**unfold** is a disclosure opening: expanding a run plays its visits open over their own height rather than dropping
them onto the page, and closing it plays the same thing backwards. Height is the only thing that moves there, because a
fade is how this page says a value changed, and showing what was already written is not a change. Clicking again part
way through reverses whatever is playing. Content the server re-rendered does lift a hair and settle as it lands. All of
it is skipped under `prefers-reduced-motion`.

An unfold is the one piece of motion that moves the whole page beneath it, and the only one whose distance is unknown
until it runs: a job's visit chain is a few lines, an expanded run is most of a screen. So it is the only one with its
own easing, which leaves and arrives gently instead of throwing the page down and slowing to a crawl, and the only one
whose duration scales, from `--unfold-min` at no distance to `--unfold-max` once a section is `--unfold-span` tall. One
fixed duration makes a three-line section feel slow and a full-screen one feel thrown.

Nothing animates unless something actually changed. The stream opens by sending the state as it stands, which is the
state the page was already rendered with, so the first frame is written in silently; a region only animates against
something the script has seen before. The same goes for the sections you left open: putting them back is not an
interaction, so they arrive already open, with the chevron already turned.

One rule sits behind all of it, and it is invisible until two row types are compared side by side: **the space around
something that opens has to live inside the clip, never on it.** A reveal cannot use `display: none`, which is not
transitionable, so it animates `grid-template-rows` from `0fr` to `1fr`; a grid child collapsed to zero height still
leaves the gap around it behind, so the space above a reveal lives inside it rather than in a gap belonging to the row,
and a grid track's automatic minimum is the item's *outer* minimum, which `min-height: 0` does not cancel, so padding on
the clipped element keeps a closed reveal a full step tall.

An unfold obeys the same rule for the same reason. A `<details>` cannot use a reveal at all, because it hides its own
content, so an unfold measures the height and plays it with the Web Animations API instead. What it measures is a
`.fold`: a bare box with no padding, border or margin, wrapping the padded body of every disclosure. Animating that body
directly would mean animating its padding too, since `height: 0` on a border-box element leaves its padding standing,
and the content would then be travelling as well as being revealed. The first line drifts down as the section opens and
the last stretch of the animation grows nothing but empty padding, which is what "expanding is janky" turned out to
mean. The fold's clip is vertical only, because the visits inside an expanded run hang on a spine one step to the left
of the box that holds them, and it is held only while the height is moving, so a resting section cannot clip a focus
ring.

Two smaller things keep an unfold honest. The page reserves its scrollbar gutter, so the first section that pushes the
document past the viewport cannot take the scrollbar's width out of the page and rewrap every line mid-animation. And
both directions are driven from the click on the summary rather than from the `toggle` event, which the browser queues:
opening measures in the same task as the click, so no frame can paint the section at full height before the animation
has anything to say about it.

One token, `--stack`, is the distance from a heading to the block beneath it, and every stack on the page uses it: a
job, an account, a visit, a run's detail, an opened disclosure.

Toasts sit in the bottom-left corner, on the gutter the rest of the page is aligned to, and arrive from the edge they
are anchored to. Centred, they landed on top of whatever was being read.

## Type and colour

One superfamily in three voices, and which voice a piece of text gets depends on who is speaking not on whether it
happens to be a sentence.

| Voice   | Face                                | Used for                                                                                                                    |
|---------|-------------------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| Label   | Plex Mono, uppercase, tracked, tiny | Names a thing: a band, a fact, a step, a state                                                                              |
| Note    | Plex Mono, sentence case, faint     | The system reporting on itself: a band's description, an account's detail, why a visit ended, an empty state                |
| Content | Plex Sans                           | Everything read as the product speaking or as an answer: titles, values, the dialog's explanation, the sign-in instructions |

A label and its value are never the same voice, mono against sans, which is what makes a table scannable. A label and
its note *are* the same voice at different weights, which is what makes the margin read as one object instead of two
systems stacked. Machine tokens are the one place a value crosses into mono: a path, a cron expression, an address, an
identifier, where the exact characters matter and the reader may want to copy them.

Half the notes on the page have a command inside them ("Account 'archive' has no stored session. Run:
`chronit login archive`"), which is the clearest argument that the system's own reporting belongs in the system's own
face. The two faces share a skeleton, so switching between them reads as one voice changing register. Numbers that
change in place use tabular figures, without which a ticking countdown shifts its own width every second.

The fonts are self-hosted: three subsetted `woff2` files, 75 kB for the whole interface, served from
`/assets/` like the stylesheet, with `font-src 'self'` in the policy. The rule was never "no webfont", it was that this
page must not phone anywhere. Plex is under the SIL Open Font License, and the licence ships beside the fonts at
`/assets/PLEX-LICENSE.txt` because the OFL requires it to travel with them.

Every foreground colour clears 4.5:1 against its background in both themes, measured rather than eyeballed. That matters
more here than the ratio usually suggests, because the labels are eleven-pixel uppercase mono, which is exactly the size
at which a merely tasteful grey stops being legible. The faintest grey on the page is 6.3:1 in dark and 4.9:1 in light.

## How it is built

Server-rendered pages on the JDK's own HTTP server. No framework, no JavaScript build step, and it works with scripting
disabled. Two decisions are worth knowing about.

**Markup comes from a typed builder, not string concatenation.** Everything on these pages originates outside the
process: server names, kick reasons, menu titles, chat. With `StringBuilder`
a single forgotten escape is an injection hole. In the builder the only way to put content into a node is `text()`,
which escapes, so the mistake is not available to make. Tests fire
`<img src=x onerror=...>` through a kick reason to keep it that way.

**The page is pushed to, not polled.** One `text/event-stream` connection to `/events` carries everything: a small JSON
snapshot, and the server-rendered fragments for the summary and the run history, so there is still only one description
of what a run looks like. The orchestrator drives it, so a job reaching the world, moving to its next server or being
stopped appears the moment it happens, and the live line under a running job names the phase it is in
(`loading resources`,
`entering the world`) instead of saying "running" for a minute and a half.

Server-sent events, not WebSockets, and that is a decision rather than a shortcut. The JDK's HTTP server, which this is
built on so the image does not have to carry a servlet container, offers no way to hand a request's socket over for a
protocol upgrade. A WebSocket would mean either a second listener on another port with its own authentication, or an
embedded server and the several megabytes that come with it. Every byte here travels one way anyway. In exchange the
browser's own
`EventSource` reconnects by itself, the session cookie authenticates the stream exactly as it authenticates every other
request, and it stays ordinary HTTP that a reverse proxy, `curl` and the network tab all understand.

Each event carries absolute state rather than a delta, which is what makes coalescing safe: a subscriber that cannot
keep up is served the newest value of each event and never a backlog, and one that reconnects needs no replay. The top
bar says whether the stream is actually connected, because on a page that no longer polls that is the one thing a reader
cannot otherwise tell; a stream refused with a 401 sends the reader back to the sign-in form instead of blinking
*offline* forever. Countdowns tick locally from embedded timestamps, so staying current costs no requests at all.
Actions go through `fetch` and answer with a toast, not a blind redirect.

The stylesheet and script are served as cacheable assets with a content-hashed URL and an ETag. A five-second sweep
covers the few things that change without announcing themselves, such as a token refreshed in the background or a fire
time passing, and it runs only while somebody is watching and publishes only when the picture has actually changed.

## Access

`/healthz` is unauthenticated and reveals nothing. Everything else needs the token when one is set, supplied either as
`Authorization: Bearer ...` for tooling, or through a small sign-in form for a browser, which exchanges it for an
`HttpOnly; SameSite=Strict` cookie. A token is deliberately not accepted as a query parameter, because that would put it
in browser history, in referrer headers and in any access log in front of the process.

Putting the dashboard behind a reverse proxy is covered in [`deploy/README.md`](../deploy/README.md).
