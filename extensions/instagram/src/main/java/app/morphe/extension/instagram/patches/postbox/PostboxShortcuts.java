package app.morphe.extension.instagram.patches.postbox;

import android.app.Application;
import android.content.Context;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Postbox (Vantage fork only): the runtime half of "Postbox shortcut lock" (L1).
 *
 * The patch strips Instagram's static shortcuts from the manifest. Instagram also publishes
 * dynamic shortcuts at runtime, and keeps re-publishing them, so this clears them when the
 * process starts and then again from {@code onActivityResumed}, at most once a minute. Pinned
 * shortcuts (ones the user dragged to the home screen) cannot be removed by an app; they are
 * disabled, which greys them out and shows {@link #DISABLED_MESSAGE} on tap.
 *
 * Deliberately NOT touched: cached / long-lived shortcuts ({@code removeLongLivedShortcuts}).
 * Those are what conversation notifications hang off, and DMs are the point of Postbox. They
 * are not offered on a launcher long-press.
 *
 * Unlike publishing, removing and disabling shortcuts is not rate limited by ShortcutManager,
 * so the once-a-minute throttle is only there to keep binder calls off the resume path.
 */
final class PostboxShortcuts {
    private static final String TAG = Postbox.TAG;

    /** Minimum gap between two checks from the resume path. */
    static final long RECHECK_MS = 60_000L;

    /** Shown by the launcher when a disabled pinned shortcut is tapped. */
    static final String DISABLED_MESSAGE = "Not available in Postbox";

    /** elapsedRealtime of the last throttled (resume path) check. Main thread only. */
    private static long lastCheckMs = Postbox.NEVER;

    private PostboxShortcuts() {
    }

    /**
     * The throttle: whether a check from the resume path is due, given the elapsed-realtime
     * clock now and at the last check ({@link Postbox#NEVER} when there has been none).
     */
    static boolean shouldRecheck(long nowMs, long lastCheckMs) {
        return Postbox.intervalElapsed(nowMs, lastCheckMs, RECHECK_MS);
    }

    /** Whether a pinned shortcut still needs disabling: already-disabled and immutable ones do not. */
    static boolean shouldDisable(boolean enabled, boolean immutable) {
        // Immutable = a manifest shortcut (possibly one that an update removed). ShortcutManager
        // throws if an app tries to change one, and the system has already disabled it.
        return enabled && !immutable;
    }

    /**
     * Clears dynamic shortcuts and disables pinned ones.
     *
     * @param force true from process start (always runs), false from the resume path (throttled).
     */
    static void enforce(Context context, boolean force) {
        try {
            long now = SystemClock.elapsedRealtime();
            if (!force && !shouldRecheck(now, lastCheckMs)) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return;
            if (!isMainProcess(context)) return;
            ShortcutManager manager = context.getSystemService(ShortcutManager.class);
            if (manager == null) return;

            // Stamp first: a failure below resets it, so the next resume retries. The run at
            // process start does not count against the throttle: Instagram publishes its
            // shortcuts after that, and the first resume has to be free to clear them.
            if (!force) lastCheckMs = now;

            List<ShortcutInfo> dynamic = manager.getDynamicShortcuts();
            int removed = dynamic == null ? 0 : dynamic.size();
            if (removed > 0) manager.removeAllDynamicShortcuts();

            int disabled = disablePinned(manager);
            if (removed > 0 || disabled > 0) {
                Log.i(TAG, "shortcuts: removed " + removed + " dynamic, disabled " + disabled + " pinned");
            }
        } catch (Throwable t) {
            // ShortcutManager throws IllegalStateException while the user is locked (direct
            // boot), among others. Never let that reach Instagram; try again on the next resume.
            lastCheckMs = Postbox.NEVER;
            Log.w(TAG, "shortcuts: " + t);
        }
    }

    private static int disablePinned(ShortcutManager manager) {
        List<ShortcutInfo> pinned = manager.getPinnedShortcuts();
        if (pinned == null || pinned.isEmpty()) return 0;
        List<String> ids = new ArrayList<>();
        for (ShortcutInfo shortcut : pinned) {
            if (shortcut != null && shouldDisable(shortcut.isEnabled(), shortcut.isImmutable())) {
                ids.add(shortcut.getId());
            }
        }
        if (ids.isEmpty()) return 0;
        try {
            manager.disableShortcuts(ids, DISABLED_MESSAGE);
            return ids.size();
        } catch (Throwable batchFailure) {
            // One id the system refuses must not keep the others enabled.
            int disabled = 0;
            for (String id : ids) {
                try {
                    manager.disableShortcuts(Collections.singletonList(id), DISABLED_MESSAGE);
                    disabled++;
                } catch (Throwable ignored) {
                    // Left as is; it is retried on the next check.
                }
            }
            return disabled;
        }
    }

    /**
     * Instagram runs several processes and each one creates the Application. Shortcuts belong
     * to the package, not the process, so only the main one needs to look after them.
     */
    private static boolean isMainProcess(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true;
        String process = Application.getProcessName();
        return process == null || process.equals(context.getPackageName());
    }
}
