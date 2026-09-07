package app.morphe.extension.twitter.patches.timelimit;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.TimeZone;

/** The real clocks, plus the build-time-only test offset. */
public final class AndroidClock implements Clock {
    private final Context context;

    public AndroidClock(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Test builds honour `adb shell setprop debug.vantagex.offset_ms N`, applied
     * to BOTH clocks so the offset looks like real elapsed time and does not
     * trip the clock-tamper check. Release builds compile testHooksEnabled()
     * to false, and the property is never read.
     */
    static long testOffsetMs() {
        if (!BuildValues.testHooksEnabled()) return 0;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String v = (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, "debug.vantagex.offset_ms", "0");
            return Long.parseLong(v.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public long wallMs() {
        return System.currentTimeMillis() + testOffsetMs();
    }

    @Override
    public long elapsedMs() {
        return SystemClock.elapsedRealtime() + testOffsetMs();
    }

    @Override
    public long bootCount() {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException e) {
            return 0;
        }
    }

    @Override
    public TimeZone zone() {
        return TimeZone.getDefault();
    }
}
