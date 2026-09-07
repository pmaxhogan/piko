package app.morphe.extension.twitter.patches.timelimit;

import static org.junit.Assert.assertEquals;

import java.util.Calendar;
import java.util.TimeZone;

import org.junit.Test;

public class DayKeysTest {
    static final TimeZone CHI = TimeZone.getTimeZone("America/Chicago");

    static long at(int y, int mo, int d, int h, int mi, TimeZone z) {
        Calendar c = Calendar.getInstance(z);
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void dayStartsAtFiveNotMidnight() {
        assertEquals(20260907, DayKeys.dayKeyOf(at(2026, 9, 7, 5, 0, CHI), CHI));
        assertEquals(20260906, DayKeys.dayKeyOf(at(2026, 9, 7, 4, 59, CHI), CHI));
        assertEquals(20260907, DayKeys.dayKeyOf(at(2026, 9, 8, 0, 30, CHI), CHI));
        assertEquals(20260907, DayKeys.dayKeyOf(at(2026, 9, 7, 23, 59, CHI), CHI));
    }

    @Test
    public void nextResetIsTheNextFiveAm() {
        long r = DayKeys.nextResetMs(at(2026, 9, 7, 13, 0, CHI), CHI);
        assertEquals(at(2026, 9, 8, 5, 0, CHI), r);
        long r2 = DayKeys.nextResetMs(at(2026, 9, 8, 3, 0, CHI), CHI);
        assertEquals(at(2026, 9, 8, 5, 0, CHI), r2);
        long r3 = DayKeys.nextResetMs(at(2026, 9, 8, 5, 0, CHI), CHI);
        assertEquals(at(2026, 9, 9, 5, 0, CHI), r3);
    }

    @Test
    public void dayKeyArithmeticCrossesMonthsAndYears() {
        assertEquals(20261001, DayKeys.nextDayKey(20260930));
        assertEquals(20270101, DayKeys.nextDayKey(20261231));
        assertEquals(20260831, DayKeys.addDays(20260907, -7));
    }

    @Test
    public void dstFallBackStillResetsAtFiveLocal() {
        // US DST ends 2026-11-01 02:00 in Chicago. The reset must still be 05:00 local.
        TimeZone z = CHI;
        assertEquals(20261031, DayKeys.dayKeyOf(at(2026, 11, 1, 4, 30, z), z));
        assertEquals(20261101, DayKeys.dayKeyOf(at(2026, 11, 1, 5, 0, z), z));
        assertEquals(at(2026, 11, 1, 5, 0, z), DayKeys.dayStartMs(20261101, z));
    }

    @Test
    public void followsThePhonesZone() {
        TimeZone tokyo = TimeZone.getTimeZone("Asia/Tokyo");
        long t = at(2026, 9, 7, 4, 30, tokyo);
        assertEquals(20260906, DayKeys.dayKeyOf(t, tokyo));
        // The same instant is the afternoon of the 6th in Chicago: also day 20260906.
        assertEquals(20260906, DayKeys.dayKeyOf(t, CHI));
    }
}
