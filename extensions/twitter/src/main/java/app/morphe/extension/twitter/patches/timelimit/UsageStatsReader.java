package app.morphe.extension.twitter.patches.timelimit;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import java.util.HashSet;
import java.util.Set;

/**
 * Asks the system how long this package has been in the foreground since the
 * day started. The system keeps these events itself, keyed by package name, so
 * they outlive force-close, reboot, clear-data and (on this device, verified
 * on-install) reinstall. Needs the "Usage access" special permission.
 */
public final class UsageStatsReader {
    private static final int ACTIVITY_STOPPED = 23;   // UsageEvents.Event.ACTIVITY_STOPPED, API 29

    private UsageStatsReader() {}

    public static boolean hasPermission(Context c) {
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            } else {
                //noinspection deprecation
                mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            }
            if (mode == AppOpsManager.MODE_DEFAULT) {
                return c.checkCallingOrSelfPermission("android.permission.PACKAGE_USAGE_STATS")
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Foreground milliseconds for this package in [beginWallMs, endWallMs]: the
     * union of every activity's resumed intervals (two activities resumed
     * back-to-back count once), with an interval still open at the end
     * extended to endWallMs.
     */
    public static long foregroundMs(Context c, long beginWallMs, long endWallMs) {
        if (endWallMs <= beginWallMs) return 0;
        try {
            UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return 0;
            String pkg = c.getPackageName();
            // Query from a bit earlier so an interval already open at beginWallMs is seen.
            UsageEvents events = usm.queryEvents(beginWallMs - 6 * 60 * 60 * 1000L, endWallMs);
            if (events == null) return 0;
            Set<String> resumed = new HashSet<>();
            long total = 0;
            long openSince = -1;
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.getNextEvent(e)) {
                if (!pkg.equals(e.getPackageName())) continue;
                int type = e.getEventType();
                String cls = e.getClassName() == null ? "" : e.getClassName();
                long t = Math.max(e.getTimeStamp(), beginWallMs);
                if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (resumed.isEmpty()) openSince = t;
                    resumed.add(cls);
                } else if (type == UsageEvents.Event.ACTIVITY_PAUSED || type == ACTIVITY_STOPPED) {
                    if (resumed.remove(cls) && resumed.isEmpty() && openSince >= 0) {
                        if (t > openSince) total += t - openSince;
                        openSince = -1;
                    }
                }
            }
            if (!resumed.isEmpty() && openSince >= 0 && endWallMs > openSince) total += endWallMs - openSince;
            return total;
        } catch (Throwable t) {
            return 0;
        }
    }
}
