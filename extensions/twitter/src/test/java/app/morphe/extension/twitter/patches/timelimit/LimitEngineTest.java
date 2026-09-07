package app.morphe.extension.twitter.patches.timelimit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.TimeZone;

import org.junit.Test;

public class LimitEngineTest {
    static final TimeZone CHI = TimeZone.getTimeZone("America/Chicago");
    static final long MIN = LimitEngine.MINUTE_MS;
    // 2026-09-07 12:00 Chicago = day 20260907
    static final long NOON = DayKeysTest.at(2026, 9, 7, 12, 0, CHI);
    static final int TODAY = 20260907;
    static final int TOMORROW = 20260908;

    LimitEngine engine(int max) {
        return new LimitEngine(new LimitConfig(max));
    }

    // ---- defaults + variants ------------------------------------------

    @Test
    public void defaultsPerBuildVariant() {
        assertEquals(30, new LimitConfig(120).defaultMinutes);
        assertEquals(30, new LimitConfig(60).defaultMinutes);
        assertEquals(30, new LimitConfig(30).defaultMinutes);
        assertEquals(15, new LimitConfig(15).defaultMinutes);
        assertEquals(15, new LimitConfig(15).maxMinutes);
    }

    @Test
    public void maxCanNeverExceedTwoHours() {
        assertEquals(120, new LimitConfig(500).maxMinutes);
        assertEquals(1, new LimitConfig(0).maxMinutes);
    }

    @Test
    public void storedLimitIsClampedToThisBuild() {
        LimitEngine e = engine(15);
        StateRecord r = new StateRecord();
        r.addChange(new StateRecord.LimitChange(NOON - 5 * MIN, TODAY - 1, 90)); // from a 2h build yesterday
        assertEquals(15, e.settledLimitMinutes(r, TODAY));
        assertFalse(e.isValidLimit(16));
        assertTrue(e.isValidLimit(15));
        assertFalse(e.isValidLimit(0));
    }

    // ---- increase next day / decrease now ------------------------------

    @Test
    public void increaseAppliesTomorrowDecreaseAppliesNow() {
        LimitEngine e = engine(120);
        StateRecord r = new StateRecord();
        assertEquals(30, e.effectiveLimitMinutes(r, TODAY));
        e.raiseSpent(r, TODAY, 25 * MIN);

        boolean tightened = e.applyChange(r, 45, NOON, TODAY);
        assertFalse(tightened);
        assertEquals(30, e.effectiveLimitMinutes(r, TODAY));      // still 30 today
        assertEquals(45, e.nextDayLimitMinutes(r, TODAY));        // 45 tomorrow
        assertEquals(45, e.effectiveLimitMinutes(r, TOMORROW));
        assertFalse(e.isLocked(r, TODAY));
        e.raiseSpent(r, TODAY, 30 * MIN);
        assertTrue(e.isLocked(r, TODAY));                          // locked at 30 despite the 45
    }

    @Test
    public void decreaseBelowSpentLocksImmediatelyAndReRaiseDoesNotUnlock() {
        LimitEngine e = engine(120);
        StateRecord r = new StateRecord();
        r.addChange(new StateRecord.LimitChange(NOON - 24 * 60 * MIN, TODAY - 1, 45));
        assertEquals(45, e.effectiveLimitMinutes(r, TODAY));
        e.raiseSpent(r, TODAY, 35 * MIN);
        assertFalse(e.isLocked(r, TODAY));

        assertTrue(e.applyChange(r, 30, NOON, TODAY));
        assertEquals(30, e.effectiveLimitMinutes(r, TODAY));
        assertTrue(e.isLocked(r, TODAY));

        assertFalse(e.applyChange(r, 40, NOON + MIN, TODAY));
        assertEquals(30, e.effectiveLimitMinutes(r, TODAY));       // no way back in today
        assertTrue(e.isLocked(r, TODAY));
        assertEquals(40, e.effectiveLimitMinutes(r, TOMORROW));    // but 40 tomorrow
        assertEquals(40, e.configuredLimitMinutes(r));
    }

    @Test
    public void lastChangeOfTheDayWinsTomorrowNotTheHighest() {
        LimitEngine e = engine(120);
        StateRecord r = new StateRecord();
        e.applyChange(r, 35, NOON, TODAY);
        e.applyChange(r, 25, NOON + MIN, TODAY);
        assertEquals(25, e.effectiveLimitMinutes(r, TODAY));
        assertEquals(25, e.effectiveLimitMinutes(r, TOMORROW));
        assertEquals(25, e.effectiveLimitMinutes(r, DayKeys.addDays(TODAY, 5)));
    }

    @Test
    public void decreaseThenIncreaseSameDayKeepsTheDecreaseTodayOnly() {
        LimitEngine e = engine(120);
        StateRecord r = new StateRecord();
        e.applyChange(r, 25, NOON, TODAY);
        e.applyChange(r, 35, NOON + MIN, TODAY);
        assertEquals(25, e.effectiveLimitMinutes(r, TODAY));
        assertEquals(35, e.effectiveLimitMinutes(r, TOMORROW));
    }

    @Test
    public void changeStampedWithAFutureDayStillTightensToday() {
        // Clock was set back after saving: the stricter reading applies.
        LimitEngine e = engine(120);
        StateRecord r = new StateRecord();
        r.addChange(new StateRecord.LimitChange(NOON, TOMORROW, 10));
        assertEquals(10, e.effectiveLimitMinutes(r, TODAY));
    }

    // ---- warnings --------------------------------------------------------

    @Test
    public void warningsFireOnceEachAtTheThresholds() {
        LimitEngine e = engine(120);
        StateRecord r = new StateRecord();                      // 30m limit
        e.raiseSpent(r, TODAY, 14 * MIN);
        assertEquals(0, e.warningToShow(r, TODAY));             // 16m left
        e.raiseSpent(r, TODAY, 15 * MIN);
        assertEquals(15, e.warningToShow(r, TODAY));
        assertEquals(0, e.warningToShow(r, TODAY));             // once
        e.raiseSpent(r, TODAY, 20 * MIN);
        assertEquals(10, e.warningToShow(r, TODAY));
        e.raiseSpent(r, TODAY, 25 * MIN);
        assertEquals(5, e.warningToShow(r, TODAY));
        e.raiseSpent(r, TODAY, 29 * MIN);
        assertEquals(1, e.warningToShow(r, TODAY));
        e.raiseSpent(r, TODAY, 30 * MIN);
        assertEquals(0, e.warningToShow(r, TODAY));             // locked, no warning
    }

    @Test
    public void elevenMinuteLimitShowsOnlyFiveAndOne() {
        LimitEngine e = engine(120);
        StateRecord r = new StateRecord();
        e.applyChange(r, 11, NOON, TODAY);
        e.raiseSpent(r, TODAY, 1 * MIN);                        // 10m left: 10m warning must NOT fire
        assertEquals(0, e.warningToShow(r, TODAY));
        e.raiseSpent(r, TODAY, 6 * MIN);                        // 5m left
        assertEquals(5, e.warningToShow(r, TODAY));
        e.raiseSpent(r, TODAY, 10 * MIN);                       // 1m left
        assertEquals(1, e.warningToShow(r, TODAY));
    }

    @Test
    public void fiveMinuteLimitShowsNothing() {
        LimitEngine e = engine(120);
        StateRecord r = new StateRecord();
        e.applyChange(r, 5, NOON, TODAY);
        e.raiseSpent(r, TODAY, 4 * MIN);
        assertEquals(0, e.warningToShow(r, TODAY));
    }

    @Test
    public void skippedWarningsAreNotStacked() {
        LimitEngine e = engine(120);
        StateRecord r = new StateRecord();
        r.addChange(new StateRecord.LimitChange(NOON - 24 * 60 * MIN, TODAY - 1, 60));
        e.raiseSpent(r, TODAY, 40 * MIN);                       // 20m left of 60
        assertEquals(0, e.warningToShow(r, TODAY));
        e.applyChange(r, 43, NOON, TODAY);                      // now 3m left: 15 and 10 skipped
        assertEquals(5, e.warningToShow(r, TODAY));
        assertEquals(0, e.warningToShow(r, TODAY));             // nothing stacked behind it
        e.raiseSpent(r, TODAY, 42 * MIN + 30 * 1000);
        assertEquals(1, e.warningToShow(r, TODAY));
    }

    @Test
    public void warningsResetWithTheDay() {
        LimitEngine e = engine(120);
        StateRecord r = new StateRecord();
        e.raiseSpent(r, TODAY, 16 * MIN);
        assertEquals(15, e.warningToShow(r, TODAY));
        e.raiseSpent(r, TOMORROW, 16 * MIN);
        assertEquals(15, e.warningToShow(r, TOMORROW));
    }

    // ---- ticking ---------------------------------------------------------

    @Test
    public void tickDeltasAreCappedAgainstStalls() {
        LimitEngine e = engine(120);
        StateRecord r = new StateRecord();
        e.addForeground(r, TODAY, 1000);
        e.addForeground(r, TODAY, 60 * 1000);                   // a stalled handler
        assertEquals(1000 + LimitEngine.MAX_TICK_DELTA_MS, r.spentMs(TODAY));
        e.addForeground(r, TODAY, -5);
        assertEquals(1000 + LimitEngine.MAX_TICK_DELTA_MS, r.spentMs(TODAY));
    }

    // ---- time resolution + rollover -------------------------------------

    @Test
    public void rollsOverAtFiveAmAndOnlyThen() {
        LimitEngine e = engine(120);
        StateRecord r = new StateRecord();
        FakeClock c = new FakeClock(DayKeysTest.at(2026, 9, 7, 23, 0, CHI), 100_000);
        LimitEngine.Now n = e.resolveNow(r, c.wallMs(), c.elapsedMs(), c.bootCount(), c.zone());
        assertEquals(TODAY, n.dayKey);
        e.raiseSpent(r, TODAY, 30 * MIN);
        assertTrue(e.isLocked(r, TODAY));

        c.pass(5 * 60 * MIN + 59 * MIN);                        // 04:59 next morning
        n = e.resolveNow(r, c.wallMs(), c.elapsedMs(), c.bootCount(), c.zone());
        assertEquals(TODAY, n.dayKey);
        assertFalse(n.rolledOver);
        assertTrue(e.isLocked(r, n.dayKey));

        c.pass(MIN);                                            // 05:00
        n = e.resolveNow(r, c.wallMs(), c.elapsedMs(), c.bootCount(), c.zone());
        assertEquals(TOMORROW, n.dayKey);
        assertTrue(n.rolledOver);
        assertFalse(e.isLocked(r, n.dayKey));
        assertEquals(0, r.spentMs(TOMORROW));
        assertEquals(30 * MIN, r.spentMs(TODAY));               // history kept for the graph
    }

    @Test
    public void clockSetForwardDoesNotResetTheDay() {
        LimitEngine e = engine(120);
        StateRecord r = new StateRecord();
        FakeClock c = new FakeClock(NOON, 100_000);
        e.resolveNow(r, c.wallMs(), c.elapsedMs(), c.bootCount(), c.zone());
        e.raiseSpent(r, TODAY, 30 * MIN);

        c.pass(10 * 1000);
        c.wall += 24 * 60 * MIN;                                // user sets the date to tomorrow
        LimitEngine.Now n = e.resolveNow(r, c.wallMs(), c.elapsedMs(), c.bootCount(), c.zone());
        assertTrue(n.clockJumpDetected);
        assertEquals(TODAY, n.dayKey);
        assertTrue(e.isLocked(r, n.dayKey));

        // Real time keeps flowing from the trusted estimate: 17h later it is really 05:00.
        c.pass(17 * 60 * MIN);
        n = e.resolveNow(r, c.wallMs(), c.elapsedMs(), c.bootCount(), c.zone());
        assertEquals(TOMORROW, n.dayKey);
        assertFalse(e.isLocked(r, n.dayKey));
    }

    @Test
    public void clockSetBackDoesNotReviveYesterday() {
        LimitEngine e = engine(120);
        StateRecord r = new StateRecord();
        FakeClock c = new FakeClock(NOON, 100_000);
        e.resolveNow(r, c.wallMs(), c.elapsedMs(), c.bootCount(), c.zone());
        e.raiseSpent(r, TODAY, 30 * MIN);
        c.pass(1000);
        c.wall -= 3 * 60 * MIN;
        LimitEngine.Now n = e.resolveNow(r, c.wallMs(), c.elapsedMs(), c.bootCount(), c.zone());
        assertTrue(n.clockJumpDetected);
        assertEquals(TODAY, n.dayKey);
        assertTrue(e.isLocked(r, n.dayKey));
    }

    @Test
    public void afterRebootWallIsTrustedButNeverRunsBackwards() {
        LimitEngine e = engine(120);
        StateRecord r = new StateRecord();
        FakeClock c = new FakeClock(NOON, 100_000);
        e.resolveNow(r, c.wallMs(), c.elapsedMs(), c.bootCount(), c.zone());
        e.raiseSpent(r, TODAY, 30 * MIN);

        c.boot++;
        c.elapsed = 5_000;                                      // fresh boot
        c.wall = NOON - 8 * 60 * MIN;                           // clock went back to 04:00 across the reboot
        LimitEngine.Now n = e.resolveNow(r, c.wallMs(), c.elapsedMs(), c.bootCount(), c.zone());
        assertTrue(n.clockJumpDetected);
        assertEquals(TODAY, n.dayKey);
        assertTrue(e.isLocked(r, n.dayKey));

        c.pass(26 * 60 * MIN);                                  // 26 real hours later: 06:00 next day
        n = e.resolveNow(r, c.wallMs(), c.elapsedMs(), c.bootCount(), c.zone());
        assertEquals(TOMORROW, n.dayKey);
        assertFalse(e.isLocked(r, n.dayKey));
    }

    @Test
    public void smallClockCorrectionsAreTolerated() {
        LimitEngine e = engine(120);
        StateRecord r = new StateRecord();
        FakeClock c = new FakeClock(NOON, 100_000);
        e.resolveNow(r, c.wallMs(), c.elapsedMs(), c.bootCount(), c.zone());
        c.pass(1000);
        c.wall += 30 * 1000;                                    // NTP nudge
        LimitEngine.Now n = e.resolveNow(r, c.wallMs(), c.elapsedMs(), c.bootCount(), c.zone());
        assertFalse(n.clockJumpDetected);
        assertEquals(c.wall, n.trustedWallMs);
    }

    @Test
    public void timezoneChangeUsesTheCurrentZone() {
        // 09:00 Chicago = 23:00 Tokyo. Moving the phone to Tokyo mid-day keeps the
        // same day key (both sides of 05:00 local are still the 7th), and the
        // next reset is 05:00 Tokyo.
        long t = DayKeysTest.at(2026, 9, 7, 9, 0, CHI);
        assertEquals(TODAY, DayKeys.dayKeyOf(t, CHI));
        assertEquals(TODAY, DayKeys.dayKeyOf(t, TimeZone.getTimeZone("Asia/Tokyo")));
    }
}
