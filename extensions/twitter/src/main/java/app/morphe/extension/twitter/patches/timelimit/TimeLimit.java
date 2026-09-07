package app.morphe.extension.twitter.patches.timelimit;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The runtime side of the daily limit: one instance per process, created from
 * Application.onCreate by the patch. It owns the record, ticks once a second
 * while any activity is in the foreground, shows the warnings, and bounces
 * every app activity to the setup screen (permissions missing) or the lock
 * screen (limit reached). Only the three Vantage screens are exempt.
 *
 * Bouncing happens at onActivityPreCreated (before the activity draws) and
 * again at onActivityResumed (safety net), and it applies to EVERY activity of
 * the app - the launcher, deep links, share targets, widgets, notification
 * taps and shortcuts all start an Activity, so all of them land here.
 */
public final class TimeLimit implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "VantageX/TimeLimit";
    private static final long TICK_MS = 1000;
    private static final long SAVE_EVERY_MS = 5000;
    private static final long USAGE_QUERY_EVERY_MS = 10_000;
    static final String SHORTCUT_ID = "vantage_x_time_limit";

    private static volatile TimeLimit instance;

    private final Application app;
    private final Clock clock;
    private final LimitEngine engine;
    private final StateStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = Collections.synchronizedList(new ArrayList<>());

    private StateRecord record;
    private LimitEngine.Now now;
    private Activity resumed;
    private boolean ticking;
    private long lastTickElapsed = -1;
    private long lastSaveElapsed;
    private long lastUsageQueryElapsed;
    private long lastUsageMs;

    private final Runnable tick = this::onTick;

    // ---- patch entry points -----------------------------------------------

    /** Called from the patched Application.onCreate. */
    @SuppressWarnings("unused")
    public static void init(Application app) {
        if (instance != null) return;
        synchronized (TimeLimit.class) {
            if (instance != null) return;
            try {
                instance = new TimeLimit(app);
            } catch (Throwable t) {
                // A crash here would take the whole app down before the first
                // frame. Log loudly; the lock still engages from the next
                // successful init because the record lives outside the app.
                Log.e(TAG, "init failed", t);
            }
        }
    }

    /** Called from the patched X settings click handler with the tapped preference key. */
    @SuppressWarnings("unused")
    public static boolean onSettingsPrefClick(String key) {
        Log.d(TAG, "settings pref click: " + key);
        if (!"pref_vantage_time_limit".equals(key)) return false;
        TimeLimit t = instance;
        if (t == null) return false;
        t.app.startActivity(new Intent(t.app, LimitConfigActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }

    public static TimeLimit get() {
        return instance;
    }

    // ---- lifecycle -----------------------------------------------------------

    private TimeLimit(Application app) {
        this.app = app;
        this.clock = new AndroidClock(app);
        this.engine = new LimitEngine(LimitConfig.fromBuild());
        this.store = new StateStore(app);
        this.record = store.load();
        this.now = engine.resolveNow(record, clock.wallMs(), clock.elapsedMs(), clock.bootCount(), clock.zone());
        refreshFromUsageStats(true);
        store.save(record);
        app.registerActivityLifecycleCallbacks(this);
        Log.i(TAG, "ready: day=" + now.dayKey + " spent=" + record.spentMs(now.dayKey) / 1000 + "s limit="
                + engine.effectiveLimitMinutes(record, now.dayKey) + "m max=" + engine.config.maxMinutes
                + "m stores=" + store.storeCount() + " testHooks=" + BuildValues.testHooksEnabled());
    }

    private boolean shortcutPublished;

    /**
     * Adds the launcher long-press shortcut. ShortcutManager rate-limits (and
     * silently refuses) calls from an app that is not in the foreground, so this
     * runs from the first onActivityResumed rather than Application.onCreate.
     */
    private void publishShortcut() {
        if (shortcutPublished) return;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return;
            ShortcutManager sm = app.getSystemService(ShortcutManager.class);
            if (sm == null) return;
            Intent i = new Intent(Intent.ACTION_VIEW).setClass(app, LimitConfigActivity.class);
            ShortcutInfo si = new ShortcutInfo.Builder(app, SHORTCUT_ID)
                    .setShortLabel("Daily time limit")
                    .setLongLabel("Daily time limit")
                    .setIcon(Icon.createWithResource(app, android.R.drawable.ic_lock_idle_alarm))
                    .setIntent(i)
                    .build();
            List<ShortcutInfo> cur = sm.getDynamicShortcuts();
            boolean present = false;
            for (ShortcutInfo s : cur) if (SHORTCUT_ID.equals(s.getId())) present = true;
            if (!present) {
                boolean ok = sm.addDynamicShortcuts(Collections.singletonList(si));
                Log.i(TAG, "shortcut published=" + ok + " rateLimited=" + sm.isRateLimitingActive());
                if (!ok) return;   // retry on a later resume
            }
            shortcutPublished = true;
        } catch (Throwable t) {
            Log.w(TAG, "shortcut: " + t);
        }
    }

    // ---- state access for the screens -----------------------------------------

    public static final class Snapshot {
        public final int dayKey;
        public final long spentMs;
        public final int limitMinutes;
        public final int nextDayLimitMinutes;
        public final int configuredMinutes;
        public final int maxMinutes;
        public final boolean locked;
        public final long nextResetWallMs;
        public final long trustedWallMs;
        public final long[] historyMs;     // last 7 days, oldest first, index 6 = today
        public final int[] historyDays;

        Snapshot(int dayKey, long spentMs, int limitMinutes, int nextDayLimitMinutes, int configuredMinutes,
                 int maxMinutes, boolean locked, long nextResetWallMs, long trustedWallMs, long[] historyMs, int[] historyDays) {
            this.dayKey = dayKey;
            this.spentMs = spentMs;
            this.limitMinutes = limitMinutes;
            this.nextDayLimitMinutes = nextDayLimitMinutes;
            this.configuredMinutes = configuredMinutes;
            this.maxMinutes = maxMinutes;
            this.locked = locked;
            this.nextResetWallMs = nextResetWallMs;
            this.trustedWallMs = trustedWallMs;
            this.historyMs = historyMs;
            this.historyDays = historyDays;
        }
    }

    public synchronized Snapshot snapshot() {
        int day = now.dayKey;
        long[] hist = new long[7];
        int[] days = new int[7];
        for (int i = 0; i < 7; i++) {
            days[i] = DayKeys.addDays(day, i - 6);
            hist[i] = record.spentMs(days[i]);
        }
        return new Snapshot(day, record.spentMs(day), engine.effectiveLimitMinutes(record, day),
                engine.nextDayLimitMinutes(record, day), engine.configuredLimitMinutes(record),
                engine.config.maxMinutes, engine.isLocked(record, day),
                DayKeys.nextResetMs(now.trustedWallMs, clock.zone()), now.trustedWallMs, hist, days);
    }

    public LimitEngine engine() {
        return engine;
    }

    /** Save a new daily limit. Returns true if it locked the app right now. */
    public synchronized boolean saveLimit(int minutes) {
        if (!engine.isValidLimit(minutes)) throw new IllegalArgumentException("limit out of range: " + minutes);
        resolve();
        engine.applyChange(record, minutes, now.trustedWallMs, now.dayKey);
        store.save(record);
        boolean locked = engine.isLocked(record, now.dayKey);
        Log.i(TAG, "limit saved: " + minutes + "m -> today " + engine.effectiveLimitMinutes(record, now.dayKey)
                + "m, tomorrow " + engine.nextDayLimitMinutes(record, now.dayKey) + "m, locked=" + locked);
        notifyListeners();
        if (locked) enforceOnResumed();
        return locked;
    }

    public void addListener(Runnable r) {
        listeners.add(r);
    }

    public void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private void notifyListeners() {
        Object[] ls = listeners.toArray();
        for (Object l : ls) {
            try {
                ((Runnable) l).run();
            } catch (Throwable t) {
                Log.w(TAG, "listener: " + t);
            }
        }
    }

    // ---- ticking ------------------------------------------------------------------

    private synchronized void resolve() {
        LimitEngine.Now n = engine.resolveNow(record, clock.wallMs(), clock.elapsedMs(), clock.bootCount(), clock.zone());
        if (n.rolledOver) Log.i(TAG, "day rolled over to " + n.dayKey);
        if (n.clockJumpDetected && (now == null || !now.clockJumpDetected)) Log.w(TAG, "clock jump detected; using elapsed-based time");
        now = n;
    }

    private void refreshFromUsageStats(boolean force) {
        long el = clock.elapsedMs();
        if (!force && el - lastUsageQueryElapsed < USAGE_QUERY_EVERY_MS) return;
        lastUsageQueryElapsed = el;
        if (!Permissions.usageAccess(app)) return;
        long begin = DayKeys.dayStartMs(now.dayKey, clock.zone());
        long ms = UsageStatsReader.foregroundMs(app, begin, clock.wallMs());
        lastUsageMs = ms;
        engine.raiseSpent(record, now.dayKey, ms);
    }

    private void startTicking() {
        if (ticking) return;
        ticking = true;
        lastTickElapsed = clock.elapsedMs();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
    }

    private void stopTicking() {
        if (!ticking) return;
        ticking = false;
        handler.removeCallbacks(tick);
        onTick();   // count the final partial second and persist
        lastTickElapsed = -1;
    }

    private void onTick() {
        int warn = 0;
        boolean lockedNow;
        synchronized (this) {
            resolve();
            long el = clock.elapsedMs();
            if (lastTickElapsed >= 0) engine.addForeground(record, now.dayKey, el - lastTickElapsed);
            lastTickElapsed = el;
            refreshFromUsageStats(false);
            lockedNow = engine.isLocked(record, now.dayKey);
            if (!lockedNow) warn = engine.warningToShow(record, now.dayKey);
            if (el - lastSaveElapsed >= SAVE_EVERY_MS || warn != 0 || lockedNow || !ticking) {
                lastSaveElapsed = el;
                store.save(record);
            }
        }
        if (warn != 0) showWarning(warn);
        if (lockedNow) enforceOnResumed();
        notifyListeners();
        if (ticking) handler.postDelayed(tick, TICK_MS);
    }

    private void showWarning(int minutes) {
        String msg = minutes == 1 ? "Vantage X: 1 minute left today" : "Vantage X: " + minutes + " minutes left today";
        Log.i(TAG, "warning toast: " + minutes + "m left");
        try {
            Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.w(TAG, "toast: " + t);
        }
    }

    // ---- enforcement ---------------------------------------------------------------

    static boolean isOurs(Activity a) {
        return a instanceof LockActivity || a instanceof LimitConfigActivity || a instanceof SetupActivity;
    }

    /** @return true if the activity was bounced (and finished). */
    private boolean enforce(Activity a) {
        if (isOurs(a)) return false;
        Class<?> target = null;
        if (!Permissions.allGranted(app)) {
            target = SetupActivity.class;
        } else {
            synchronized (this) {
                resolve();
                if (engine.isLocked(record, now.dayKey)) target = LockActivity.class;
            }
        }
        if (target == null) return false;
        Log.i(TAG, "bouncing " + a.getClass().getName() + " -> " + target.getSimpleName());
        Intent i = new Intent(a, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        a.startActivity(i);
        a.finish();
        return true;
    }

    private void enforceOnResumed() {
        Activity a = resumed;
        if (a != null && !a.isFinishing()) enforce(a);
    }

    /** Called by the lock screen once the day rolled over so the app can be used again. */
    void relaunchApp(Activity from) {
        Intent i = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            from.startActivity(i);
        }
        from.finish();
    }

    Context context() {
        return app;
    }

    @Override
    public void onActivityPreCreated(Activity a, Bundle b) {
        enforce(a);
    }

    @Override
    public void onActivityCreated(Activity a, Bundle b) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) enforce(a);
    }

    @Override
    public void onActivityStarted(Activity a) {
    }

    @Override
    public void onActivityResumed(Activity a) {
        resumed = a;
        if (enforce(a)) return;
        publishShortcut();
        startTicking();
    }

    @Override
    public void onActivityPaused(Activity a) {
        if (resumed == a) resumed = null;
        stopTicking();
    }

    @Override
    public void onActivityStopped(Activity a) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity a, Bundle b) {
    }

    @Override
    public void onActivityDestroyed(Activity a) {
        if (resumed == a) resumed = null;
    }
}
