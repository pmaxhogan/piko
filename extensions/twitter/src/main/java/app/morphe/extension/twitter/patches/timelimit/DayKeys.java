package app.morphe.extension.twitter.patches.timelimit;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * A "day" for the limit runs from 05:00 local to the next 05:00 local, not
 * midnight to midnight. A day key is the yyyyMMdd of the local date the day
 * STARTED on, so 2026-09-08 03:30 local belongs to day 20260907.
 */
public final class DayKeys {
    public static final int RESET_HOUR = 5;

    private DayKeys() {}

    public static int dayKeyOf(long wallMs, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(wallMs);
        // Before 05:00 local the day still belongs to the previous date. This is
        // a comparison on the local hour, not "minus five hours of millis", so
        // it stays right on the 23- and 25-hour DST days.
        if (c.get(Calendar.HOUR_OF_DAY) < RESET_HOUR) c.add(Calendar.DAY_OF_MONTH, -1);
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }

    /** 05:00 local on the day key's date. */
    public static long dayStartMs(int dayKey, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.clear();
        c.set(dayKey / 10000, (dayKey / 100) % 100 - 1, dayKey % 100, RESET_HOUR, 0, 0);
        return c.getTimeInMillis();
    }

    /** The day key after this one (calendar-correct, handles month/year ends). */
    public static int nextDayKey(int dayKey) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(dayKey / 10000, (dayKey / 100) % 100 - 1, dayKey % 100, 12, 0, 0);
        c.add(Calendar.DAY_OF_MONTH, 1);
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }

    public static int addDays(int dayKey, int days) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(dayKey / 10000, (dayKey / 100) % 100 - 1, dayKey % 100, 12, 0, 0);
        c.add(Calendar.DAY_OF_MONTH, days);
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }

    /** The next 05:00 local strictly after wallMs. */
    public static long nextResetMs(long wallMs, TimeZone zone) {
        return dayStartMs(nextDayKey(dayKeyOf(wallMs, zone)), zone);
    }
}
