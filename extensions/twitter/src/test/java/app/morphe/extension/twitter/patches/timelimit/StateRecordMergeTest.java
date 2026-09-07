package app.morphe.extension.twitter.patches.timelimit;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StateRecordMergeTest {
    @Test
    public void mergeIsMonotoneAndOrderIndependent() {
        StateRecord a = new StateRecord();
        a.addChange(new StateRecord.LimitChange(1000, 20260907, 20));
        a.usage(20260907).spentMs = 600_000;
        a.usage(20260907).warningsShown = 0b1;
        a.today = 20260907;
        a.anchor = new StateRecord.TimeAnchor(1000, 10, 1);

        StateRecord b = new StateRecord();                      // an older/wiped copy
        b.addChange(new StateRecord.LimitChange(500, 20260906, 60));
        b.usage(20260907).spentMs = 100_000;
        b.usage(20260907).warningsShown = 0b10;
        b.usage(20260906).spentMs = 5;
        b.today = 20260906;
        b.anchor = new StateRecord.TimeAnchor(900, 5, 1);

        StateRecord ab = a.copy(); ab.mergeFrom(b);
        StateRecord ba = b.copy(); ba.mergeFrom(a);
        for (StateRecord m : new StateRecord[]{ab, ba}) {
            assertEquals(2, m.changes.size());
            assertEquals(60, m.changes.get(0).minutes);         // sorted by day then time
            assertEquals(20, m.changes.get(1).minutes);
            assertEquals(600_000, m.spentMs(20260907));         // max
            assertEquals(0b11, m.usage(20260907).warningsShown); // OR
            assertEquals(5, m.spentMs(20260906));
            assertEquals(20260907, m.today);                    // never backwards
            assertEquals(1000, m.anchor.wallMs);                // newest
        }
        // The merged copy is stricter than either wiped half: the 20-minute change survives.
        LimitEngine e = new LimitEngine(new LimitConfig(120));
        assertEquals(20, e.effectiveLimitMinutes(ab, 20260907));
    }

    @Test
    public void trimsToBoundedSize() {
        StateRecord r = new StateRecord();
        for (int i = 0; i < 100; i++) r.addChange(new StateRecord.LimitChange(i, 20260901, 10 + (i % 5)));
        assertEquals(StateRecord.MAX_CHANGES, r.changes.size());
        for (int i = 0; i < 60; i++) r.usage(20260101 + i).spentMs = i;
        StateRecord other = new StateRecord();
        r.mergeFrom(other);
        assertEquals(StateRecord.MAX_DAYS, r.days.size());
        assertEquals(Integer.valueOf(20260160), r.days.lastKey());
    }
}
