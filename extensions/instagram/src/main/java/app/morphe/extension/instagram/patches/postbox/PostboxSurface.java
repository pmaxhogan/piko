package app.morphe.extension.instagram.patches.postbox;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Postbox (Vantage fork only): the runtime of "Postbox surface lock" (D4, D6, D12, L2).
 *
 * What a tap INSIDE the app can still reach, as found on a phone with 447.0.0.55.81. The link
 * router only sees links that arrive from outside, and the network block only empties a page;
 * this looks at what is on screen:
 *
 * - Forbidden pages (L2, D12): a hashtag in a caption opens the search results page, a post's
 *   audio label the audio page, a post's location the place page. Each has a view of its own
 *   ({@link #FORBIDDEN_VIEWS}); when one of them is on screen the activity showing it is
 *   finished and the app lands on the inbox, the way the link router bounces a link.
 * - Home title (D6): the "Instagram" wordmark opens a Following / Favorites menu. Inside the
 *   Home action bar, and only there, the title stops taking clicks and its chevron is hidden.
 * - Home placeholder (D4): with the timeline blocked the loading shimmer under the stories tray
 *   never ends. It is made invisible; the tray is never searched and never touched.
 *
 * There is no fingerprint and no Instagram class name here. {@link Postbox} hands over every
 * activity once it has started; one global-layout listener per activity window looks through
 * the visible views for the id names below, at most once per {@link #PASS_GAP_MS}. The names
 * are resolved the way {@link PostboxProfile} resolves its own, and unlike those they were read
 * off the running app (uiautomator dumps of each screen), not guessed.
 *
 * Everything runs on the main thread, inside Instagram's own view code. Nothing here may throw.
 */
final class PostboxSurface {
    private static final String TAG = Postbox.TAG;
    private static final String LOG = "surface: ";

    // =========================================================================================
    // VIEW NAMES, from uiautomator dumps of 447.0.0.55.81:
    //
    //   search results ("#tag" header, For you / Accounts / Audio / Tags):
    //     serp_journey_header_container > serp_journey_header_query_text, search_tab_bar_layout
    //   audio page:   clips_pivot_page_fragment > videos_list, audio_page_floating_buttons_container
    //   place page:   bottom_sheet > media_map_bottom_sheet_container > fragment_container >
    //                 location_detail_action_bar + recycler_view (location_info, Top / Recent grid)
    //   Home:         <fragment root>
    //                   sticky_header_list > refreshable_container > list
    //                     reels_tray_container            the stories tray: KEEP
    //                     shimmer_content                 the endless placeholder: INVISIBLE
    //                   action_bar_LinearLayout > main_feed_action_bar > action_bar_action_buttons
    //                     action_bar_title_view           clickable, opens the feed menu: LOCK
    //                       title_logo_chevron_container > title_logo
    // =========================================================================================

    /**
     * One row per view that only a forbidden page has: its id name, and what the bounce line in
     * logcat calls the page. Deliberately NOT here:
     *
     * - {@code friend_map_friends_content_container}, {@code map_container}: the friend map is
     *   a Messages feature, and "See location" on a post lands on that same map first. Only the
     *   place page that "See more" opens from it is forbidden.
     * - anything of the inbox search ({@code search_bar_real_field},
     *   {@code search_results_container}), the single reel viewer
     *   ({@code clips_viewer_view_pager}), a single post, profiles, stories, the activity feed.
     */
    static final String[][] FORBIDDEN_VIEWS = {
            {"serp_journey_header_container", "serp"},
            {"clips_pivot_page_fragment", "audio"},
            {"media_map_bottom_sheet_container", "place"},
            {"location_detail_action_bar", "place"},
    };

    static final String HOME_ACTION_BAR = "main_feed_action_bar";
    /** Also the title of other screens' action bars, so only ever looked for inside the Home one. */
    static final String HOME_TITLE = "action_bar_title_view";
    /** Neither was drawn in the dumps; both names exist in the app's id table. */
    static final String[] HOME_TITLE_CHEVRONS = {"title_chevron", "action_bar_title_chevron"};
    static final String HOME_SHIMMER = "shimmer_content";
    static final String STORIES_TRAY = "reels_tray_container";

    // =========================================================================================

    /** Minimum gap between two looks at one window; a layout inside it gets one look at its end. */
    static final long PASS_GAP_MS = 150L;

    /**
     * How far above the Home action bar its fragment's root can be. In the dump it is two
     * levels up; one more is still inside the Home tab's own page.
     */
    private static final int HOME_ROOT_LEVELS = 3;
    private static final int MAX_ANCESTORS = 48;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_VISITED = 10_000;

    /** Main thread only, like everything below. */
    private static Ids ids;

    /**
     * One watch per live activity, removed in {@link #unwatch}. A watch refers to its activity
     * and its views weakly, so an entry that was never removed goes with the activity.
     */
    private static final Map<Activity, Watch> WATCHES = new WeakHashMap<>();

    private PostboxSurface() {
    }

    // ---- pure -----------------------------------------------------------------------------------

    /** The page a forbidden view belongs to, or null for a view that is not one. */
    static String forbiddenPage(String idName) {
        if (idName == null) return null;
        for (String[] row : FORBIDDEN_VIEWS) {
            if (row[0].equals(idName)) return row[1];
        }
        return null;
    }

    /** The throttle: how long from now until a window may be looked at again; 0 = now. */
    static long passDelay(long nowMs, long lastPassMs) {
        if (Postbox.intervalElapsed(nowMs, lastPassMs, PASS_GAP_MS)) return 0L;
        return PASS_GAP_MS - (nowMs - lastPassMs);
    }

    // ---- entry points ---------------------------------------------------------------------------

    /** Called by {@link Postbox} from onActivityStarted, for every activity. Never throws. */
    static void watch(Activity activity) {
        try {
            if (activity == null || WATCHES.containsKey(activity)) return;
            Window window = activity.getWindow();
            // Started means onCreate is over, so asking for the decor view cannot get in the
            // way of the activity's own window setup any more.
            View decor = window == null ? null : window.getDecorView();
            if (decor == null) return;
            Watch watch = new Watch(activity, decor);
            WATCHES.put(activity, watch);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(watch);
        } catch (Throwable t) {
            Log.w(TAG, LOG + "watch: " + t);
        }
    }

    /** Called by {@link Postbox} from onActivityDestroyed. Never throws. */
    static void unwatch(Activity activity) {
        try {
            Watch watch = WATCHES.remove(activity);
            if (watch != null) watch.stop();
        } catch (Throwable t) {
            Log.w(TAG, LOG + "unwatch: " + t);
        }
    }

    // ---- names to ids ---------------------------------------------------------------------------

    /** The id names above, resolved against this app's resources. 0 = no such name. */
    private static final class Ids {
        final int[] forbidden;
        final int homeBar;
        final int homeTitle;
        final int[] chevrons;
        final int shimmer;
        final int tray;
        final int[] ceilings;

        Ids(int[] forbidden, int homeBar, int homeTitle, int[] chevrons, int shimmer, int tray, int[] ceilings) {
            this.forbidden = forbidden;
            this.homeBar = homeBar;
            this.homeTitle = homeTitle;
            this.chevrons = chevrons;
            this.shimmer = shimmer;
            this.tray = tray;
            this.ceilings = ceilings;
        }
    }

    private static Ids resolveIds(View decor) {
        Ids cached = ids;
        if (cached != null) return cached;

        // The resource package is read off a view that has an id from the app's own table.
        // A window that has no such view yet has nothing to find either.
        View sample = firstAppView(decor, 0);
        if (sample == null) return null;
        Resources resources = decor.getResources();
        Set<String> packages = PostboxProfile.resourcePackagesOf(sample);

        String[] forbiddenNames = new String[FORBIDDEN_VIEWS.length];
        for (int index = 0; index < forbiddenNames.length; index++) forbiddenNames[index] = FORBIDDEN_VIEWS[index][0];

        StringBuilder summary = new StringBuilder("ids[");
        int[] forbidden = PostboxProfile.resolveAll(resources, packages, forbiddenNames, summary);
        int[] home = PostboxProfile.resolveAll(resources, packages,
                new String[] {HOME_ACTION_BAR, HOME_TITLE, HOME_SHIMMER, STORIES_TRAY}, summary);
        int[] chevrons = PostboxProfile.resolveAll(resources, packages, HOME_TITLE_CHEVRONS, summary);
        summary.append("packages=").append(packages).append(']');
        int[] ceilings = PostboxProfile.resolveAll(resources, packages, PostboxProfile.CEILING_VIEWS, null);

        // Kept whatever came out: the package was read off a real view, so a name that did not
        // resolve now never will, and looking it up again on every pass would only cost time.
        // This line is therefore logged once, and an UNRESOLVED in it is a name to fix.
        Ids resolved = new Ids(forbidden, home[0], home[1], chevrons, home[2], home[3], ceilings);
        ids = resolved;
        Log.i(TAG, LOG + "watching, " + summary);
        return resolved;
    }

    /** The first view, depth first, whose id comes from the app's own resource table. */
    private static View firstAppView(View view, int depth) {
        if (PostboxProfile.resourcePackageOf(view.getResources(), view.getId()) != null) return view;
        if (depth >= MAX_DEPTH || !(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0, count = group.getChildCount(); index < count; index++) {
            View child = group.getChildAt(index);
            View found = child == null ? null : firstAppView(child, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    // ---- one activity window --------------------------------------------------------------------

    /**
     * Looks at one activity's views after layout, for as long as the activity lives. The
     * listener sits on the observer of the activity's own window and is removed in
     * {@link #stop()}; the activity and its decor view are only referred to weakly.
     */
    private static final class Watch implements ViewTreeObserver.OnGlobalLayoutListener, Runnable {
        private final WeakReference<Activity> activity;
        private final WeakReference<View> decor;
        private final ArrayList<View> homeBars = new ArrayList<>();
        private final ArrayList<View> shimmers = new ArrayList<>();
        private final Rect visibleRect = new Rect();

        private long lastPassMs = Postbox.NEVER;
        private boolean passPosted;
        private boolean stopped;
        private int visited;
        /** Index into FORBIDDEN_VIEWS of the forbidden view this pass found on screen, or -1. */
        private int forbidden;
        /** The title this watch locked last; the same one is locked again without a log line. */
        private WeakReference<View> lockedTitle;
        private WeakReference<View> hiddenShimmer;

        Watch(Activity activity, View decor) {
            this.activity = new WeakReference<>(activity);
            this.decor = new WeakReference<>(decor);
        }

        void stop() {
            stopped = true;
            View decor = this.decor.get();
            if (decor == null) return;
            decor.removeCallbacks(this);
            // The observer the view has NOW: the one a listener was added to before the window
            // was attached has been merged into it.
            ViewTreeObserver observer = decor.getViewTreeObserver();
            if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
        }

        @Override
        public void onGlobalLayout() {
            try {
                View decor = this.decor.get();
                if (stopped || passPosted || decor == null) return;
                long delay = passDelay(SystemClock.elapsedRealtime(), lastPassMs);
                if (delay <= 0) {
                    pass(decor);
                } else {
                    passPosted = true;
                    decor.postDelayed(this, delay);
                }
            } catch (Throwable t) {
                Log.w(TAG, LOG + "layout: " + t);
            }
        }

        /** The look a layout inside the gap was promised. */
        @Override
        public void run() {
            try {
                passPosted = false;
                View decor = this.decor.get();
                if (!stopped && decor != null) pass(decor);
            } catch (Throwable t) {
                Log.w(TAG, LOG + "pass: " + t);
            }
        }

        private void pass(View decor) {
            lastPassMs = SystemClock.elapsedRealtime();
            Activity host = activity.get();
            if (host == null || host.isFinishing() || !decor.isShown()) return;
            Ids resolved = resolveIds(decor);
            if (resolved == null) return;

            homeBars.clear();
            shimmers.clear();
            visited = 0;
            forbidden = -1;
            collect(decor, 0, resolved);

            if (forbidden >= 0) {
                bounce(host, FORBIDDEN_VIEWS[forbidden][1]);
            } else {
                for (View bar : homeBars) lockHome(bar, resolved);
            }
            homeBars.clear();
            shimmers.clear();
        }

        /**
         * One walk over the views that are visible. Stops at the first forbidden view that is
         * really on screen, and otherwise collects the Home action bar and the placeholder.
         */
        private void collect(View view, int depth, Ids resolved) {
            if (forbidden >= 0 || ++visited > MAX_VISITED || view.getVisibility() != View.VISIBLE) return;
            int id = view.getId();
            if (id != View.NO_ID) {
                int match = PostboxProfile.indexOf(resolved.forbidden, id);
                if (match >= 0) {
                    // A page that is laid out but scrolled or swiped out of the window is not
                    // on screen (and is not in a uiautomator dump either).
                    if (view.getWidth() > 0 && view.getHeight() > 0 && view.getGlobalVisibleRect(visibleRect)) {
                        forbidden = match;
                    }
                    return;
                }
                if (id == resolved.homeBar) {
                    homeBars.add(view);
                    return;
                }
                if (id == resolved.shimmer) {
                    shimmers.add(view);
                    return;
                }
                // The stories tray is the one thing Home is for.
                if (id == resolved.tray) return;
            }
            if (depth >= MAX_DEPTH || !(view instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) view;
            for (int index = 0, count = group.getChildCount(); index < count; index++) {
                View child = group.getChildAt(index);
                if (child != null) collect(child, depth + 1, resolved);
            }
        }

        // ---- forbidden pages (L2, D12) ----

        /**
         * Finishes the activity that shows the page, whatever happens next, and starts the app
         * on the inbox through the link router's relaunch (one loop guard for both). When the
         * guard holds the relaunch back, the page is still gone: the user is on whatever was
         * under it.
         */
        private void bounce(Activity host, String page) {
            // This activity is done for; a layout pass on its way out must not bounce again.
            stopped = true;
            Log.i(TAG, LOG + "bounce " + page + " (" + host.getClass().getSimpleName() + ")");
            try {
                PostboxLinkRouter.relaunchOnInbox(host);
            } finally {
                host.finish();
            }
        }

        // ---- Home (D4, D6) ----

        private void lockHome(View bar, Ids resolved) {
            View title = resolved.homeTitle == 0 ? null : bar.findViewById(resolved.homeTitle);
            if (title != null) {
                boolean changed = lockClicks(title, 0);
                for (int chevron : resolved.chevrons) {
                    View view = chevron == 0 ? null : bar.findViewById(chevron);
                    if (view != null && view.getVisibility() != View.GONE) {
                        view.setVisibility(View.GONE);
                        changed = true;
                    }
                }
                // Instagram sets its listener again whenever it refills the bar; only a title
                // not seen before is worth a line.
                if (changed && (lockedTitle == null || lockedTitle.get() != title)) {
                    lockedTitle = new WeakReference<>(title);
                    Log.i(TAG, LOG + "home title locked");
                }
            }

            if (shimmers.isEmpty()) return;
            View home = homeRootOf(bar, resolved);
            if (home == null) return;
            for (View shimmer : shimmers) {
                if (!isInside(shimmer, home)) continue;
                // INVISIBLE, not GONE: it is a row of a list, and the list keeps its layout.
                shimmer.setVisibility(View.INVISIBLE);
                if (hiddenShimmer == null || hiddenShimmer.get() != shimmer) {
                    hiddenShimmer = new WeakReference<>(shimmer);
                    Log.i(TAG, LOG + "home placeholder hidden");
                }
            }
        }

        /**
         * Takes every way to click away from the title and the views inside it: the listeners,
         * and the clickable flags that touch, long-press and the accessibility click action all
         * go by. Returns whether anything had to change.
         */
        private boolean lockClicks(View view, int depth) {
            boolean changed = view.isClickable() || view.isLongClickable() || view.hasOnClickListeners();
            if (changed) {
                // Listeners first: setting one, even to null, makes the view clickable again.
                view.setOnClickListener(null);
                view.setOnLongClickListener(null);
                view.setOnTouchListener(null);
                view.setClickable(false);
                view.setLongClickable(false);
                view.setContextClickable(false);
            }
            if (depth >= MAX_DEPTH || !(view instanceof ViewGroup)) return changed;
            ViewGroup group = (ViewGroup) view;
            for (int index = 0, count = group.getChildCount(); index < count; index++) {
                View child = group.getChildAt(index);
                if (child != null && lockClicks(child, depth + 1)) changed = true;
            }
            return changed;
        }

        /**
         * The root view of the fragment the Home action bar sits in: the highest ancestor
         * within {@link #HOME_ROOT_LEVELS} that is not one of the containers holding more than
         * one screen.
         */
        private View homeRootOf(View bar, Ids resolved) {
            View root = null;
            ViewParent parent = bar.getParent();
            for (int level = 0; parent instanceof View && level < HOME_ROOT_LEVELS; level++) {
                View ancestor = (View) parent;
                int id = ancestor.getId();
                if (id == android.R.id.content || PostboxProfile.indexOf(resolved.ceilings, id) >= 0) break;
                root = ancestor;
                parent = ancestor.getParent();
            }
            return root;
        }

        private boolean isInside(View view, View root) {
            ViewParent parent = view.getParent();
            for (int level = 0; parent instanceof View && level < MAX_ANCESTORS; level++) {
                if (parent == root) return true;
                parent = parent.getParent();
            }
            return false;
        }
    }
}
