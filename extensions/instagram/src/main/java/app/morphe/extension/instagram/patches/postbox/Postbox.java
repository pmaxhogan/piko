package app.morphe.extension.instagram.patches.postbox;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/**
 * Postbox (Vantage fork only): the one runtime entry point of the Postbox patches.
 *
 * The internal Postbox core patch calls {@link #init(Context)} from Instagram's
 * Application.onCreate (piko's own init seam). It registers ONE ActivityLifecycleCallbacks and
 * fans the events out to the features, each of which is switched on by its own patch through a
 * {@link PostboxBuild} getter. With no feature on, nothing is registered at all.
 *
 * Every activity start goes through here: the launcher, deep links, share targets, notification
 * taps and shortcuts all create an Activity. Everything in this class runs on Instagram's main
 * thread inside Instagram's own lifecycle, so nothing here may throw.
 */
public final class Postbox implements Application.ActivityLifecycleCallbacks, ComponentCallbacks2 {
    static final String TAG = "Postbox";

    /** "Has not happened yet", as a value of the elapsed-realtime clock. */
    static final long NEVER = Long.MIN_VALUE;

    private static volatile boolean initialized;

    private final Application app;
    private final boolean shortcutLock;
    private final boolean linkRouter;
    private final boolean profileGrid;
    private final boolean surfaceLock;

    /** Activities of this process between onStart and onStop. Zero = the UI is in the background. */
    private int started;

    private Postbox(Application app, boolean shortcutLock, boolean linkRouter, boolean profileGrid,
                    boolean surfaceLock) {
        this.app = app;
        this.shortcutLock = shortcutLock;
        this.linkRouter = linkRouter;
        this.profileGrid = profileGrid;
        this.surfaceLock = surfaceLock;
    }

    /** Patch entry point. Safe to call more than once and from any process of the app. */
    @SuppressWarnings("unused")
    public static void init(Context context) {
        if (initialized) return;
        synchronized (Postbox.class) {
            if (initialized) return;
            initialized = true;
        }
        try {
            boolean shortcutLock = PostboxBuild.shortcutLock();
            boolean linkRouter = PostboxBuild.linkRouter();
            boolean profileGrid = PostboxBuild.profileGrid();
            boolean surfaceLock = PostboxBuild.surfaceLock();
            if (!shortcutLock && !linkRouter && !profileGrid && !surfaceLock) return;

            Application app = applicationOf(context);
            if (app == null) {
                Log.e(TAG, "init: no Application behind " + context + ", Postbox lifecycle features are OFF");
                return;
            }
            Postbox postbox = new Postbox(app, shortcutLock, linkRouter, profileGrid, surfaceLock);
            app.registerActivityLifecycleCallbacks(postbox);
            // feature: Postbox shortcut lock
            if (shortcutLock) {
                // For onTrimMemory, the system's own "your UI is hidden now".
                app.registerComponentCallbacks(postbox);
                PostboxShortcuts.enforce(app, true);
            }
            Log.i(TAG, "ready: shortcutLock=" + shortcutLock + " linkRouter=" + linkRouter
                    + " profileGrid=" + profileGrid + " surfaceLock=" + surfaceLock
                    + " networkBlock=" + PostboxBuild.networkBlock()
                    + " traceNetwork=" + PostboxBuild.traceNetwork());
        } catch (Throwable t) {
            // A crash here would take the app down before its first frame.
            Log.e(TAG, "init failed", t);
        }
    }

    private static Application applicationOf(Context context) {
        if (context instanceof Application) return (Application) context;
        Context application = context == null ? null : context.getApplicationContext();
        return application instanceof Application ? (Application) application : null;
    }

    /**
     * The shared throttle decision: whether {@code intervalMs} has passed since {@code lastMs}.
     * True when it never happened, and when the clock reads earlier than the last time (which
     * elapsedRealtime does not do; a bogus timestamp must not be able to stop the clock).
     */
    static boolean intervalElapsed(long nowMs, long lastMs, long intervalMs) {
        if (lastMs == NEVER) return true;
        if (nowMs < lastMs) return true;
        return nowMs - lastMs >= intervalMs;
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    /** Before the activity's own onCreate has seen its intent. API 29+. */
    @Override
    public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
        onActivityCreating(activity);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        // Below API 29 there is no pre-created callback; this one runs from inside
        // Activity.onCreate, which is the earliest the platform offers.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) onActivityCreating(activity);
    }

    // The feature entry points below never throw: each catches everything itself.

    private void onActivityCreating(Activity activity) {
        // feature: Postbox link router
        if (linkRouter) PostboxLinkRouter.route(activity);
        // feature: Postbox profile grid
        if (profileGrid) PostboxProfile.onActivityCreating(activity);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        // feature: Postbox link router
        if (linkRouter) PostboxLinkRouter.route(activity);
        // feature: Postbox shortcut lock
        if (shortcutLock) PostboxShortcuts.enforce(app, false);
    }

    @Override
    public void onActivityStarted(Activity activity) {
        started++;
        // feature: Postbox surface lock. Not earlier: the window's views exist from here on.
        if (surfaceLock) PostboxSurface.watch(activity);
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
        if (started > 0) started--;
        // feature: Postbox shortcut lock. The last activity stopped: the app left the screen,
        // which is when Instagram publishes its shortcuts. A rotation is not that.
        if (shortcutLock && started == 0 && !activity.isChangingConfigurations()) {
            PostboxShortcuts.onBackground(app);
        }
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        // feature: Postbox surface lock
        if (surfaceLock) PostboxSurface.unwatch(activity);
    }

    // ---- ComponentCallbacks2 (registered for the shortcut lock only) ---------------------------

    @Override
    public void onTrimMemory(int level) {
        // feature: Postbox shortcut lock. The second way to learn that the UI is hidden.
        if (shortcutLock && level == TRIM_MEMORY_UI_HIDDEN) PostboxShortcuts.onBackground(app);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
    }

    @Override
    public void onLowMemory() {
    }
}
