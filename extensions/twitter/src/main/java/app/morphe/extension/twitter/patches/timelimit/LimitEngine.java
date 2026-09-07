package app.morphe.extension.twitter.patches.timelimit;

import java.util.TimeZone;

/**
 * The pure rules of the daily limit. No Android imports, no I/O: it takes a
 * {@link StateRecord} plus the current time and returns decisions. Everything
 * user-visible (lock, warnings, the numbers on the config screen) derives from
 * these methods, and they are the part covered by JUnit tests.
 *
 * Rules (from the spec):
 *  - The day resets at 05:00 local, see {@link DayKeys}.
 *  - Decreases apply immediately; increases apply from the next day.
 *  - The next day's limit is the LAST change made on any earlier day, whatever
 *    direction it went (so 30 -> 35 -> 25 today means 25 today and 25 tomorrow).
 *    Both rules fall out of one formula: effective(day) = min(settled(day),
 *    every change made on that day), settled(day) = last change before it.
 *  - Warnings at 15/10/5/1 minutes remaining, each once per day, skipped when
 *    the limit is within 5 minutes of the threshold, and never stacked: only
 *    the lowest applicable unshown threshold fires and the higher ones are
 *    marked as shown.
 *  - Wall-clock edits never reset the day early: elapsedRealtime is the truth
 *    within a boot, and the day key never moves backwards.
 */
public final class LimitEngine {
    /** Warning thresholds, minutes remaining, descending. Bit i of DayUsage.warningsShown = index i. */
    public static final int[] WARNING_MINUTES = {15, 10, 5, 1};
    /** A threshold is suppressed unless the limit exceeds it by MORE than this. */
    public static final int WARNING_MARGIN_MINUTES = 5;
    /** Wall vs elapsed disagreement beyond this = someone edited the clock. */
    public static final long CLOCK_JUMP_TOLERANCE_MS = 2 * 60 * 1000L;
    /** A single tick can never add more than this (guards a stalled handler / deep sleep). */
    public static final long MAX_TICK_DELTA_MS = 5 * 1000L;

    public static final long MINUTE_MS = 60 * 1000L;

    public final LimitConfig config;

    public LimitEngine(LimitConfig config) {
        this.config = config;
    }

    // ---- limit rules -----------------------------------------------------

    /** The limit in force at the START of dayKey: the last change made on an earlier day, else the default. */
    public int settledLimitMinutes(StateRecord r, int dayKey) {
        int v = config.defaultMinutes;
        for (StateRecord.LimitChange c : r.changes) {
            if (c.dayKey < dayKey) v = c.minutes;   // changes are sorted, so the last one wins
        }
        return config.clamp(v);
    }

    /** The limit actually enforced on dayKey. */
    public int effectiveLimitMinutes(StateRecord r, int dayKey) {
        int v = settledLimitMinutes(r, dayKey);
        for (StateRecord.LimitChange c : r.changes) {
            // Changes on this day only ever tighten it. Changes stamped with a
            // LATER day (a clock that was set back after saving) tighten too:
            // the safe reading of an impossible timestamp is the stricter one.
            if (c.dayKey >= dayKey) v = Math.min(v, config.clamp(c.minutes));
        }
        return v;
    }

    /** The limit tomorrow will start with if nothing else changes today. */
    public int nextDayLimitMinutes(StateRecord r, int dayKey) {
        return settledLimitMinutes(r, DayKeys.nextDayKey(dayKey));
    }

    /** The most recently saved value, i.e. what the config screen should show as "your limit". */
    public int configuredLimitMinutes(StateRecord r) {
        if (r.changes.isEmpty()) return config.defaultMinutes;
        return config.clamp(r.changes.get(r.changes.size() - 1).minutes);
    }

    /** Record a user change. Returns true if it tightened today's limit. */
    public boolean applyChange(StateRecord r, int minutes, long trustedWallMs, int dayKey) {
        int before = effectiveLimitMinutes(r, dayKey);
        r.addChange(new StateRecord.LimitChange(trustedWallMs, dayKey, config.clamp(minutes)));
        return effectiveLimitMinutes(r, dayKey) < before;
    }

    public boolean isValidLimit(int minutes) {
        return minutes >= LimitConfig.MIN_MINUTES && minutes <= config.maxMinutes;
    }

    // ---- lock + remaining ----------------------------------------------

    public long limitMs(StateRecord r, int dayKey) {
        return effectiveLimitMinutes(r, dayKey) * MINUTE_MS;
    }

    public long remainingMs(StateRecord r, int dayKey) {
        return limitMs(r, dayKey) - r.spentMs(dayKey);
    }

    public boolean isLocked(StateRecord r, int dayKey) {
        return remainingMs(r, dayKey) <= 0;
    }

    // ---- time resolution -----------------------------------------------

    /** What "now" resolved to after tamper checks. */
    public static final class Now {
        public final long trustedWallMs;
        public final int dayKey;
        public final boolean clockJumpDetected;
        public final boolean rolledOver;

        Now(long trustedWallMs, int dayKey, boolean clockJumpDetected, boolean rolledOver) {
            this.trustedWallMs = trustedWallMs;
            this.dayKey = dayKey;
            this.clockJumpDetected = clockJumpDetected;
            this.rolledOver = rolledOver;
        }
    }

    /**
     * Work out the trusted current time and day, advancing the record's day if a
     * real 05:00 has passed. Within one boot the wall clock must agree with
     * elapsedRealtime since the last anchor; if it does not, the elapsed-based
     * estimate is used instead, so setting the clock forward (or back) does
     * nothing. Across a reboot elapsed cannot be compared, so the wall clock is
     * trusted but never allowed to run backwards past the anchor. The day key
     * itself never decreases, so a rolled-back clock cannot revive yesterday.
     */
    public Now resolveNow(StateRecord r, long wallMs, long elapsedMs, long bootCount, TimeZone zone) {
        long trusted = wallMs;
        boolean jump = false;
        StateRecord.TimeAnchor a = r.anchor;
        if (a != null) {
            if (a.bootCount == bootCount && elapsedMs >= a.elapsedMs) {
                long estimate = a.wallMs + (elapsedMs - a.elapsedMs);
                if (Math.abs(wallMs - estimate) > CLOCK_JUMP_TOLERANCE_MS) {
                    trusted = estimate;
                    jump = true;
                }
            } else if (wallMs < a.wallMs) {
                trusted = a.wallMs;
                jump = true;
            }
        }
        int day = DayKeys.dayKeyOf(trusted, zone);
        boolean rolled = false;
        if (r.today == 0) {
            r.today = day;
        } else if (day > r.today) {
            r.today = day;
            rolled = true;
        } else {
            day = r.today;   // never backwards
        }
        r.usage(day);        // make sure today exists for the graph/merge
        r.anchor = new StateRecord.TimeAnchor(trusted, elapsedMs, bootCount);
        return new Now(trusted, day, jump, rolled);
    }

    // ---- ticking -------------------------------------------------------

    /** Add foreground time measured by elapsedRealtime deltas. Returns the new spent value. */
    public long addForeground(StateRecord r, int dayKey, long deltaElapsedMs) {
        if (deltaElapsedMs <= 0) return r.spentMs(dayKey);
        long d = Math.min(deltaElapsedMs, MAX_TICK_DELTA_MS);
        StateRecord.DayUsage u = r.usage(dayKey);
        u.spentMs += d;
        return u.spentMs;
    }

    /** Raise today's spent time to an externally measured value (UsageStats, another store). */
    public void raiseSpent(StateRecord r, int dayKey, long spentMs) {
        StateRecord.DayUsage u = r.usage(dayKey);
        if (spentMs > u.spentMs) u.spentMs = spentMs;
    }

    // ---- warnings ------------------------------------------------------

    /**
     * Which warning (minutes remaining) to show now, or 0 for none. Marks the
     * returned threshold and every higher one as shown, so a limit decrease
     * that skips past 15 and 10 shows only the 5-minute toast.
     */
    public int warningToShow(StateRecord r, int dayKey) {
        long remaining = remainingMs(r, dayKey);
        if (remaining <= 0) return 0;
        int limit = effectiveLimitMinutes(r, dayKey);
        StateRecord.DayUsage u = r.usage(dayKey);
        int pick = -1;
        for (int i = 0; i < WARNING_MINUTES.length; i++) {
            int t = WARNING_MINUTES[i];
            if (limit - t <= WARNING_MARGIN_MINUTES) continue;        // limit within 5m of the trigger
            if ((u.warningsShown & (1 << i)) != 0) continue;           // already shown today
            if (remaining <= t * MINUTE_MS) pick = i;                  // descending, so the last hit is the lowest
        }
        if (pick < 0) return 0;
        for (int i = 0; i <= pick; i++) u.warningsShown |= (1 << i);
        return WARNING_MINUTES[pick];
    }
}
