# Accounts and tokens

Microsoft authentication uses the device code flow. `chronit login main` prints a short code and a
link, you enter it on any device with a browser, and the container polls until it completes. No
redirect URI and no public HTTPS endpoint are involved, which is the only workable arrangement for
something running headless.

## Why you should almost never log in again

Three token lifetimes are involved, and conflating them is where the folklore about re-authorising
every quarter comes from:

| Token                   | Lasts     | What it is for                                        |
|-------------------------|-----------|-------------------------------------------------------|
| Minecraft access token  | ~24 hours | what the server checks when you join                  |
| Microsoft access token  | ~1 hour   | mints the Minecraft one                               |
| Microsoft refresh token | 90 days   | mints both, and replaces itself every time it is used |

That last row is the important one. Microsoft issues a new refresh token on every refresh, and each
one starts a fresh ninety days. The ninety days is a limit on being left alone, not a countdown to
an unavoidable login. A daemon that touches its sessions on a schedule never reaches it, and the
only things that genuinely end a session are a password change, an explicit revocation, or leaving
the process off for three months.

So chronit keeps sessions warm the way a desktop launcher does. Prism Launcher, for instance, walks
its account list on a timer and renews anything close to expiry instead of waiting for the game to
start. The daemon refreshes every Microsoft account at startup and every six hours after that:

```yaml
auth:
  refreshOnStart: true    # renew at startup, so a session that went stale while the process
                          # was down is found immediately rather than by the 3am run
  refreshInterval: 6h     # background sweep; 0 refreshes only when a visit needs it
  refreshMargin: 30m      # treat a token as due this long before it expires
```

Refreshing is proactive rather than reactive. A token with a minute left on it is no use, because
the server revalidates it against Mojang during the join and a join is not instant, so anything
inside the margin is renewed before it reaches the driver. The sweep looks one whole interval
further ahead than that (`refreshInterval + refreshMargin`), because a token that would lapse
between two sweeps has to be caught by the earlier one.

`chronit accounts` shows how long each session would survive being left alone. Add `--refresh` to
renew them all first, which is the only way to actually find out whether a session still works,
since the plain report is read from disk and makes no request. It exits non-zero when a login is
genuinely due, so a monitoring check can catch it, and the daemon says so at startup.

Refresh failures are told apart instead of lumped together. A Microsoft outage is retried and logged
as such, while a revoked or expired session is what asks for `chronit login`. A visit does not burn
its retries on the latter.

## Storage and identity

Set `CHRONIT_SECRET_KEY` to encrypt the stored session at rest with AES-GCM. Keep the value stable:
changing it makes the existing token file unreadable, which is reported as an error rather than as a
missing session, so a login cannot overwrite a session that was intact all along.

If you set `clientId` to your own Azure registration after having logged in without one, the stored
session is refused, not silently kept, because a refresh token only works for the application
that issued it. Run `chronit login` once and it is yours from then on.

`auth: OFFLINE` accounts need no login and derive their UUID exactly as a vanilla server does for
`online-mode=false`, so the bot keeps a stable identity across visits. They only work against
offline-mode servers, which makes them right for local testing.
