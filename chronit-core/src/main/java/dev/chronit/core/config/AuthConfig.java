package dev.chronit.core.config;

import java.time.Duration;

/**
 * How stored Microsoft sessions are kept alive.
 *
 * <p>A Microsoft refresh token is replaced by a new one every time it is used, and each replacement
 * starts a fresh ninety-day window. Nothing about that ninety days is a countdown to an unavoidable
 * login: it is an <em>inactivity</em> limit. A deployment that touches its sessions on a schedule
 * therefore never needs an interactive login at all, which is what the background refresher is for.
 * The only things that genuinely end a session are a password change, an explicit revocation, or
 * leaving the process off for longer than the window.
 *
 * @param refreshOnStart  refresh every Microsoft account as the daemon comes up, so a session that
 *                        went stale while the process was down is discovered immediately rather
 *                        than during the first scheduled visit
 * @param refreshInterval how often the background sweep runs. Zero disables it, which leaves
 *                        refreshing to happen on the critical path of a visit
 * @param refreshMargin   how long before expiry a token is considered due. Nothing is gained by
 *                        using a token with four seconds left on it: the server revalidates it
 *                        against Mojang during the join, and the join is not instant
 */
public record AuthConfig(
        Boolean refreshOnStart,
        Duration refreshInterval,
        Duration refreshMargin) {

    /**
     * Six hours between sweeps keeps the ninety-day window open with three orders of magnitude to
     * spare while costing a handful of requests a day, and a half-hour margin is far longer than
     * any join sequence.
     */
    public static final AuthConfig DEFAULTS =
            new AuthConfig(Boolean.TRUE, Duration.ofHours(6), Duration.ofMinutes(30));

    /**
     * How long a Microsoft refresh token survives without being used.
     *
     * <p>Microsoft documents ninety days for everything except single-page applications. It is not
     * reported by the API, so it can only be counted from the last successful refresh.
     */
    public static final Duration SESSION_LIFETIME = Duration.ofDays(90);

    public boolean refreshOnStartOrDefault() {
        return refreshOnStart != null ? refreshOnStart : DEFAULTS.refreshOnStart();
    }

    public Duration refreshIntervalOrDefault() {
        return refreshInterval != null ? refreshInterval : DEFAULTS.refreshInterval();
    }

    public Duration refreshMarginOrDefault() {
        return refreshMargin != null ? refreshMargin : DEFAULTS.refreshMargin();
    }

    public boolean isBackgroundRefreshEnabled() {
        Duration interval = refreshIntervalOrDefault();
        return !interval.isZero() && !interval.isNegative();
    }

    /**
     * How far ahead the background sweep looks.
     *
     * <p>A margin alone is not enough for a sweep: with a half-hour margin and a six-hour interval,
     * a token would have to expire inside a half-hour window that the sweep only visits every six
     * hours, so most of the time it would expire unnoticed. Looking one whole interval further
     * ahead means anything that would lapse before the next sweep is dealt with by this one.
     */
    public Duration sweepHorizon() {
        return refreshIntervalOrDefault().plus(refreshMarginOrDefault());
    }
}
