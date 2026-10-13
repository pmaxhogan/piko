package app.morphe.extension.instagram.patches.postbox;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
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
 * {@link PostboxNav#sanitizeStartupIntent(Intent)}. Before either, a link to a single reel is
 * turned into its single-post form, see {@link #openAsPost}.
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
            if (data != null && openAsPost(activity, intent, data)) return;
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

    /**
     * L2: a link to a single reel is opened through the single-post viewer, because the Reels
     * viewer that Instagram would pick cannot load its first item in Postbox (see
     * {@link PostboxLinks#reelAsPostLink}). Returns whether the link was one and was dealt with.
     *
     * Two cases, told apart by whether the activity is exported, i.e. whether another app can
     * have sent it the link:
     *
     * - Exported (Instagram's link entry point, which picks a handler by the link): the link in
     *   its intent is replaced before its onCreate reads it. It then picks the post handler
     *   itself, and the Reels handler never exists.
     * - Not exported (a handler the entry point has already picked for the reel form, which is
     *   what is left when the link reached the entry point through onNewIntent): the post link
     *   is sent to the app afresh and this activity is finished with its link taken away, so
     *   the failed viewer is not left on the back stack. Only for a VIEW intent: an activity
     *   the app starts for a reel it already holds (one opened from a chat) is not a link
     *   being routed and is left exactly as it is.
     *
     * No loop: what both cases produce is a /p/ link, which is never a reel link.
     */
    private static boolean openAsPost(Activity activity, Intent intent, Uri data) {
        String postLink;
        try {
            postLink = PostboxLinks.reelAsPostLink(data.getScheme(), data.getHost(), data.getPath());
        } catch (Throwable t) {
            // Left to isForbidden, which bounces a data URI that cannot be read.
            return false;
        }
        if (postLink == null) return false;
        Uri post = Uri.parse(postLink);

        if (isExported(activity)) {
            Intent rewritten = new Intent(intent);
            rewritten.setData(post);
            // A post link is allowed by construction; the startup-tab extra is still checked.
            PostboxNav.sanitizeStartupIntent(rewritten);
            activity.setIntent(rewritten);
            // No shortcode in the log.
            Log.i(TAG, "reel link -> post viewer, in place (" + activity.getClass().getSimpleName() + ")");
            return true;
        }
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) return false;

        Intent view = new Intent(Intent.ACTION_VIEW, post);
        view.setPackage(activity.getPackageName());
        // If nothing in the app takes the link this throws, and the activity is left as it was.
        activity.startActivity(view);
        activity.setIntent(withoutLink(intent));
        activity.finish();
        Log.i(TAG, "reel link -> post viewer, sent again (" + activity.getClass().getSimpleName() + ")");
        return true;
    }

    /**
     * Whether another app can start this activity. Instagram's link entry point and its aliases
     * are declared disabled in the manifest and switched on at runtime, hence the flag. When it
     * cannot be told, the answer is yes: swapping the link in place on a handler that is not an
     * entry point leaves, at worst, the error page there is today, while sending the link again
     * from the entry point itself could lose it.
     */
    private static boolean isExported(Activity activity) {
        try {
            return activity.getPackageManager()
                    .getActivityInfo(activity.getComponentName(), PackageManager.MATCH_DISABLED_COMPONENTS)
                    .exported;
        } catch (Throwable t) {
            return true;
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
        if (relaunchOnInbox(activity)) activity.finish();
    }

    /**
     * Step 2 on its own: starts the launcher activity on the inbox in a cleared task, unless the
     * loop guard says no. Returns whether it did; finishing {@code activity} is the caller's
     * move. "Postbox surface lock" bounces through here too, so the two share one guard.
     */
    static boolean relaunchOnInbox(Activity activity) {
        long now = SystemClock.elapsedRealtime();
        if (!relaunchAllowed(now, lastRelaunchMs)) {
            Log.w(TAG, "bounce: second bounce within " + RELAUNCH_GUARD_MS + " ms, staying in place");
            return false;
        }
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
        if (launch == null) {
            Log.w(TAG, "bounce: no launcher activity, staying in place");
            return false;
        }
        launch.putExtra(PostboxNav.STARTUP_TAB_EXTRA, PostboxNav.INBOX_TAB);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        lastRelaunchMs = now;
        activity.startActivity(launch);
        return true;
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
