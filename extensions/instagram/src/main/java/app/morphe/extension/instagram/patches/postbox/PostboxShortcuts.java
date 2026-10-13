package app.morphe.extension.instagram.patches.postbox;

import android.app.Application;
import android.content.Context;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Postbox (Vantage fork only): the runtime half of "Postbox shortcut lock" (L1).
 *
 * The patch strips Instagram's static shortcuts from the manifest. Instagram also publishes
 * dynamic shortcuts at runtime, and it does so when the app goes to the BACKGROUND (seen on
 * 447.0.0.55.81: none while the app is in front, seven within three seconds of HOME: the camera,
 * the activity feed, share, the inbox and three conversations). Three layers, in this order:
 *
 * - Publishing: the patch redirects every call Instagram's code makes to
 *   ShortcutManager.setDynamicShortcuts / addDynamicShortcuts / pushDynamicShortcut to the
 *   methods of the same name here, which publish only what {@link #keeps} lets through. Today
 *   that is nothing.
 * - Clearing when the UI goes to the background ({@link #onBackground}): at once and again
 *   after {@link #BACKGROUND_PASSES_MS}. This is what holds if a publish path was missed.
 * - Clearing when the process starts and from {@code onActivityResumed}, at most once a minute.
 *
 * Pinned shortcuts (ones the user dragged to the home screen) cannot be removed by an app; they
 * are disabled, which greys them out and shows {@link #DISABLED_MESSAGE} on tap.
 *
 * The conversation shortcuts are dynamic AND long-lived, so removeAllDynamicShortcuts takes them
 * off the launcher long-press with the rest; L1 says all of them go. What is deliberately not
 * called is {@code removeLongLivedShortcuts}: a copy the system has cached for a conversation
 * notification stays cached, and that is not offered on a long-press.
 *
 * Unlike publishing, removing and disabling shortcuts is not rate limited by ShortcutManager,
 * so the once-a-minute throttle is only there to keep binder calls off the resume path.
 *
 * Public because Instagram's own classes call the three publish methods.
 */
public final class PostboxShortcuts {
    private static final String TAG = Postbox.TAG;

    /** Minimum gap between two checks from the resume path. */
    static final long RECHECK_MS = 60_000L;

    /** Shown by the launcher when a disabled pinned shortcut is tapped. */
    static final String DISABLED_MESSAGE = "Not available in Postbox";

    /**
     * When the clearing passes run, counted from the moment the UI went to the background.
     * Instagram published within three seconds of it on 447.0.0.55.81; the late pass is for a
     * slower device. A process the system has frozen by then runs it when it thaws.
     */
    static final long[] BACKGROUND_PASSES_MS = {0L, 1_000L, 5_000L, 15_000L};

    /** elapsedRealtime of the last throttled (resume path) check. Main thread only. */
    private static long lastCheckMs = Postbox.NEVER;

    /** Token of the delayed background passes, so a new round replaces the one still pending. */
    private static final Object BACKGROUND_TOKEN = new Object();

    private static Handler mainHandler;

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

    // ---- publishing ---------------------------------------------------------------------------

    /**
     * The ONE rule for which dynamic shortcuts may exist, given a shortcut's categories. Both
     * the publish methods below and the clearing in {@link #enforce} go by it.
     *
     * L1: none. Should the conversation targets come back one day, this is the line to change:
     * on 447.0.0.55.81 they are the ones with the category {@code actions.intent.SEND_MESSAGE}
     * (the other four have no category), so it would read
     * {@code return categories != null && categories.contains("actions.intent.SEND_MESSAGE");}
     */
    static boolean keeps(Set<String> categories) {
        return false;
    }

    private static boolean keeps(ShortcutInfo shortcut) {
        return shortcut != null && keeps(shortcut.getCategories());
    }

    /** The shortcuts of one publish call that may go through, in the order they came. */
    private static List<ShortcutInfo> kept(List<ShortcutInfo> shortcuts) {
        List<ShortcutInfo> kept = new ArrayList<>();
        if (shortcuts != null) {
            for (ShortcutInfo shortcut : shortcuts) {
                if (keeps(shortcut)) kept.add(shortcut);
            }
        }
        if (shortcuts != null && kept.size() < shortcuts.size()) {
            Log.i(TAG, "shortcuts: dropped " + (shortcuts.size() - kept.size()) + " of " + shortcuts.size()
                    + " at publish");
        }
        return kept;
    }

    /**
     * Patch entry point: stands in for every {@code manager.setDynamicShortcuts(shortcuts)} in
     * Instagram's code. Returns what the real call returns on success, and does not throw.
     * (The patch that puts these three calls in place is the one that turns the lock on; with
     * the lock off each of them is simply the real call.)
     */
    @SuppressWarnings("unused")
    public static boolean setDynamicShortcuts(ShortcutManager manager, List<ShortcutInfo> shortcuts) {
        if (!PostboxBuild.shortcutLock()) return manager.setDynamicShortcuts(shortcuts);
        try {
            List<ShortcutInfo> kept = kept(shortcuts);
            if (!kept.isEmpty()) return manager.setDynamicShortcuts(kept);
            // "Replace them all with these", and none of these may exist. Removing is not rate
            // limited, publishing an empty list would be.
            manager.removeAllDynamicShortcuts();
        } catch (Throwable t) {
            Log.w(TAG, "shortcuts: set: " + t);
        }
        return true;
    }

    /** Patch entry point: stands in for every {@code manager.addDynamicShortcuts(shortcuts)}. */
    @SuppressWarnings("unused")
    public static boolean addDynamicShortcuts(ShortcutManager manager, List<ShortcutInfo> shortcuts) {
        if (!PostboxBuild.shortcutLock()) return manager.addDynamicShortcuts(shortcuts);
        try {
            List<ShortcutInfo> kept = kept(shortcuts);
            if (!kept.isEmpty()) return manager.addDynamicShortcuts(kept);
        } catch (Throwable t) {
            Log.w(TAG, "shortcuts: add: " + t);
        }
        return true;
    }

    /** Patch entry point: stands in for every {@code manager.pushDynamicShortcut(shortcut)}. */
    @SuppressWarnings("unused")
    public static void pushDynamicShortcut(ShortcutManager manager, ShortcutInfo shortcut) {
        if (!PostboxBuild.shortcutLock()) {
            manager.pushDynamicShortcut(shortcut);
            return;
        }
        try {
            if (!kept(Collections.singletonList(shortcut)).isEmpty()) manager.pushDynamicShortcut(shortcut);
        } catch (Throwable t) {
            Log.w(TAG, "shortcuts: push: " + t);
        }
    }

    // ---- clearing -----------------------------------------------------------------------------

    /**
     * The app's UI went to the background (its last started activity stopped, or the system
     * said so), which is when Instagram publishes. Clears now and again after each of
     * {@link #BACKGROUND_PASSES_MS}. Never throws.
     */
    static void onBackground(final Context context) {
        try {
            if (!isMainProcess(context)) return;
            if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.removeCallbacksAndMessages(BACKGROUND_TOKEN);
            long now = SystemClock.uptimeMillis();
            for (long delay : BACKGROUND_PASSES_MS) {
                if (delay <= 0) {
                    enforce(context, true);
                } else {
                    mainHandler.postAtTime(() -> enforce(context, true), BACKGROUND_TOKEN, now + delay);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "shortcuts: background: " + t);
        }
    }

    /**
     * Clears dynamic shortcuts and disables pinned ones.
     *
     * @param force true from process start and the background passes (always runs), false from
     *              the resume path (throttled).
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
            List<String> drop = new ArrayList<>();
            if (dynamic != null) {
                for (ShortcutInfo shortcut : dynamic) {
                    if (shortcut != null && !keeps(shortcut)) drop.add(shortcut.getId());
                }
            }
            int removed = drop.size();
            if (removed > 0) {
                if (removed == dynamic.size()) {
                    manager.removeAllDynamicShortcuts();
                } else {
                    manager.removeDynamicShortcuts(drop);
                }
            }

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
