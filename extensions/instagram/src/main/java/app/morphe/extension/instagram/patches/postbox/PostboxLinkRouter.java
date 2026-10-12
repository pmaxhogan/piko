package app.morphe.extension.instagram.patches.postbox;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

/**
 * Postbox (Vantage fork only): the runtime half of "Postbox link router" (L2, L3, D12).
 *
 * {@link Postbox} calls {@link #route(Activity)} for EVERY activity of the app before the
 * activity's own onCreate has looked at its intent, and again on every resume. Two things are
 * checked: the intent's data URI (a deep link, whoever sent it: another app, a notification, a
 * shortcut, adb) against {@link PostboxLinks}, and its startup-tab extra against
 * {@link PostboxNav#sanitizeStartupIntent(Intent)}.
 *
 * Why also on resume: a link delivered to an activity that is already running arrives through
 * onNewIntent, which has no lifecycle callback. An activity that adopts such an intent with
 * setIntent() is caught on the resume that always follows; that is later than ideal (the
 * activity has already seen the link) but it still ends on the inbox. An activity that handles
 * onNewIntent without setIntent() is invisible from here, which is what the network block is
 * the second layer for.
 */
final class PostboxLinkRouter {
    private static final String TAG = Postbox.TAG;

    /**
     * How a forbidden link is bounced. true = strategy (b): take the link away, finish the
     * activity and start the app's launcher activity on the inbox. false = strategy (a): take
     * the link away and let the activity carry on. See {@link #bounce(Activity)}.
     */
    private static final boolean RELAUNCH_ON_BOUNCE = true;

    /**
     * A second relaunch inside this window is not made: the bounce degrades to strategy (a).
     * A relaunch whose own result gets bounced would otherwise restart the app forever.
     */
    static final long RELAUNCH_GUARD_MS = 5_000L;

    /** elapsedRealtime of the last relaunch. Main thread only. */
    private static long lastRelaunchMs = Postbox.NEVER;

    private PostboxLinkRouter() {
    }

    /** The loop guard: whether a bounce may relaunch the app, or has to stay in place. */
    static boolean relaunchAllowed(long nowMs, long lastRelaunchMs) {
        return Postbox.intervalElapsed(nowMs, lastRelaunchMs, RELAUNCH_GUARD_MS);
    }

    /** Never throws. */
    static void route(Activity activity) {
        try {
            Intent intent = activity.getIntent();
            if (intent == null) return;
            Uri data = intent.getData();
            if (data != null && isForbidden(data)) {
                bounce(activity);
                return;
            }
            // No forbidden link: the startup-tab extra is the other way to name a tab.
            PostboxNav.sanitizeStartupIntent(intent);
        } catch (Throwable t) {
            Log.w(TAG, "link router: " + t);
        }
    }

    private static boolean isForbidden(Uri data) {
        String scheme = null;
        String host = null;
        String path = null;
        boolean forbidden;
        try {
            scheme = data.getScheme();
            host = data.getHost();
            path = data.getPath();
            forbidden = PostboxLinks.classify(scheme, host, path, data.getEncodedQuery())
                    == PostboxLinks.Verdict.BOUNCE;
        } catch (Throwable t) {
            // A data URI that cannot even be read is not one to wave through.
            forbidden = true;
        }
        if (forbidden) Log.i(TAG, "bounce " + PostboxLinks.describe(scheme, host, path));
        return forbidden;
    }

    /**
     * The ONE place that decides how a forbidden link is bounced. Swap the strategy here (or
     * flip {@link #RELAUNCH_ON_BOUNCE}) after a device test; nothing else depends on it.
     *
     * Step 1, always: replace the activity's intent with a copy that has no data URI and names
     * the inbox as the startup tab. Whatever happens next, this activity can no longer read the
     * link. It is a copy rather than a blank intent so that any other extras the activity
     * expects are still there.
     *
     * Step 2, strategy (b), the default: start the launcher activity with the inbox extra in a
     * cleared task, and finish this activity. Chosen over (a) "let the activity carry on"
     * because the activities that receive links are trampolines this code has never seen: handed
     * an intent without a link, one may finish silently (the user is dropped back in the other
     * app), fall through to Home, or sit there blank. Only starting a known-good entry point
     * guarantees the user lands somewhere. A fresh launcher activity lands on the inbox twice
     * over: through the startup-tab extra if it reads it, and through piko's cold-start default
     * (Messages, from PostboxNav.NAV_CONFIG) if it does not. finish() + startActivity from
     * onActivityPreCreated is the same move the X "Daily time limit" lock makes for every
     * activity, with the same NEW_TASK | CLEAR_TASK flags.
     *
     * No loop: the relaunch intent and the replacement intent carry no data URI, and
     * {@link PostboxLinks} allows "no link" by construction (PostboxLinksTest pins it), so a
     * bounce cannot be bounced. If Instagram itself were to put a forbidden link back on the
     * relaunched activity, the guard below makes the second bounce stay in place.
     */
    private static void bounce(Activity activity) {
        Intent original = activity.getIntent();
        activity.setIntent(withoutLink(original));

        if (!RELAUNCH_ON_BOUNCE) return;
        long now = SystemClock.elapsedRealtime();
        if (!relaunchAllowed(now, lastRelaunchMs)) {
            Log.w(TAG, "bounce: second bounce within " + RELAUNCH_GUARD_MS + " ms, staying in place");
            return;
        }
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
        if (launch == null) {
            Log.w(TAG, "bounce: no launcher activity, staying in place");
            return;
        }
        launch.putExtra(PostboxNav.STARTUP_TAB_EXTRA, PostboxNav.INBOX_TAB);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        lastRelaunchMs = now;
        activity.startActivity(launch);
        activity.finish();
    }

    /** A copy of the intent with nothing left that carries the link, aimed at the inbox. */
    private static Intent withoutLink(Intent original) {
        Intent clean = original == null ? new Intent() : new Intent(original);
        clean.setData(null); // also clears the MIME type
        clean.setClipData(null);
        clean.setSelector(null);
        clean.putExtra(PostboxNav.STARTUP_TAB_EXTRA, PostboxNav.INBOX_TAB);
        return clean;
    }
}
