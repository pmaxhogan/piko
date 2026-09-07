package app.morphe.extension.twitter.patches.timelimit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * Everything the limit needs to remember, kept as one small value object that
 * is written identically to every store and merged back when read. Merging is
 * deliberately monotone: unions of limit changes, max of time spent, OR of
 * warnings shown, newest anchor. A store that was deleted, wiped or rolled back
 * therefore never makes the limit more permissive - the other copies win.
 */
public final class StateRecord {
    /** A user change of the daily limit. */
    public static final class LimitChange {
        public final long atMs;
        public final int dayKey;
        public final int minutes;

        public LimitChange(long atMs, int dayKey, int minutes) {
            this.atMs = atMs;
            this.dayKey = dayKey;
            this.minutes = minutes;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof LimitChange)) return false;
            LimitChange c = (LimitChange) o;
            return c.atMs == atMs && c.dayKey == dayKey && c.minutes == minutes;
        }

        @Override
        public int hashCode() {
            return (int) (atMs ^ (atMs >>> 32)) * 31 + dayKey * 7 + minutes;
        }
    }

    /** Time spent and warnings shown on one day. */
    public static final class DayUsage {
        public long spentMs;
        /** Bit i set = warning threshold i (see LimitEngine.WARNING_MINUTES) already shown. */
        public int warningsShown;

        public DayUsage(long spentMs, int warningsShown) {
            this.spentMs = spentMs;
            this.warningsShown = warningsShown;
        }
    }

    /** Last trusted (wall, elapsed, boot) triple, for clock-tamper detection. */
    public static final class TimeAnchor {
        public final long wallMs;
        public final long elapsedMs;
        public final long bootCount;

        public TimeAnchor(long wallMs, long elapsedMs, long bootCount) {
            this.wallMs = wallMs;
            this.elapsedMs = elapsedMs;
            this.bootCount = bootCount;
        }
    }

    public static final Comparator<LimitChange> CHANGE_ORDER = (a, b) -> {
        if (a.dayKey != b.dayKey) return Integer.compare(a.dayKey, b.dayKey);
        return Long.compare(a.atMs, b.atMs);
    };

    /** Keep the record small enough for every store (Settings.System values are strings). */
    static final int MAX_CHANGES = 40;
    static final int MAX_DAYS = 21;

    public final List<LimitChange> changes = new ArrayList<>();
    public final TreeMap<Integer, DayUsage> days = new TreeMap<>();
    /** The day the record currently counts toward; 0 = never used. Never moves backwards. */
    public int today;
    public TimeAnchor anchor;

    public DayUsage usage(int dayKey) {
        DayUsage u = days.get(dayKey);
        if (u == null) {
            u = new DayUsage(0, 0);
            days.put(dayKey, u);
        }
        return u;
    }

    public long spentMs(int dayKey) {
        DayUsage u = days.get(dayKey);
        return u == null ? 0 : u.spentMs;
    }

    public void addChange(LimitChange c) {
        if (!changes.contains(c)) changes.add(c);
        Collections.sort(changes, CHANGE_ORDER);
        trim();
    }

    /** Fold another (already HMAC-verified) copy into this one. */
    public void mergeFrom(StateRecord o) {
        for (LimitChange c : o.changes) if (!changes.contains(c)) changes.add(c);
        Collections.sort(changes, CHANGE_ORDER);
        for (java.util.Map.Entry<Integer, DayUsage> e : o.days.entrySet()) {
            DayUsage mine = days.get(e.getKey());
            if (mine == null) {
                days.put(e.getKey(), new DayUsage(e.getValue().spentMs, e.getValue().warningsShown));
            } else {
                mine.spentMs = Math.max(mine.spentMs, e.getValue().spentMs);
                mine.warningsShown |= e.getValue().warningsShown;
            }
        }
        today = Math.max(today, o.today);
        if (o.anchor != null && (anchor == null || o.anchor.wallMs > anchor.wallMs)) anchor = o.anchor;
        trim();
    }

    private void trim() {
        while (changes.size() > MAX_CHANGES) changes.remove(0);
        while (days.size() > MAX_DAYS) days.remove(days.firstKey());
    }

    public StateRecord copy() {
        StateRecord r = new StateRecord();
        r.mergeFrom(this);
        return r;
    }
}
