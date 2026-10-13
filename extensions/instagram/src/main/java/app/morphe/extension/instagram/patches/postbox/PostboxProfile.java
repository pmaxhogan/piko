package app.morphe.extension.instagram.patches.postbox;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import com.instagram.common.session.UserSession;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.patches.postbox.PostboxProfileNet.RehideBudget;
import app.morphe.extension.instagram.patches.postbox.PostboxProfileNet.Verdict;

/**
 * Postbox (Vantage fork only): the view half of "Postbox profile grid" (D8).
 *
 * Somebody else's profile keeps its header, bio, highlights and follower lists; the tab strip
 * and the pager with the posts and reels grids under it are hidden. The user's own profile is
 * never touched.
 *
 * The patch prepends {@link #onProfileBound} to piko's own
 * {@code ActionBarPatch.userProfileActionBarButton}, which Instagram's profile action bar
 * builder calls (through piko's hook) every time it fills the bar, with the live UserSession
 * and the user the profile shows. That is all this needs: who is looking, whose profile it is,
 * and a view inside that profile's own view tree to start from.
 *
 * There is no fingerprint and no Instagram class name here. Views are found by resource NAME
 * at runtime. NOTHING IN THIS FILE HAS BEEN RUN INSIDE INSTAGRAM: the names below come from
 * public UI dumps of other Instagram builds, not from the APK piko supports. Every bind logs
 * (tag {@code Postbox}) whose profile it was, which names resolved and what was hidden; when
 * the tab strip or the pager is not found it lists the ids that ARE there. So the first run on
 * a device says what to correct.
 *
 * Everything runs on the main thread, inside Instagram's own view code. Nothing here may throw.
 */
public final class PostboxProfile {
    private static final String TAG = Postbox.TAG;
    private static final String LOG = "profile grid: ";

    // =========================================================================================
    // VIEW NAMES. ALL UNCONFIRMED until checked against the resources of the real APK
    // (`aapt2 dump resources base.apk | grep -E 'id/(<name>)'`, or the logcat lines of a first
    // run). Sources: a decoded layout of Instagram 87 (layout_profile_tabbed_viewpager.xml, 2019)
    // and two public uiautomator / accessibility dumps of the profile screen from 2026-09 and
    // 2026-10, which agree with each other:
    //
    //   user_detail_fragment                    the profile fragment's root view
    //     coordinator_root_layout
    //       tab_appbar > profile_header_container
    //         profile_header_fixed_list         header, bio, highlights, buttons: KEEP
    //         profile_tabs_container            the grid/reels/tagged tab strip: HIDE
    //           profile_tab_layout > ... > profile_tab_icon_view
    //       profile_viewpager                   the pager holding the grids: HIDE
    //     profile_action_bar > action_bar_layout > right_action_bar_buttons   <- the ViewGroup
    //                                                                            the hook gets
    // =========================================================================================

    /**
     * Views set to GONE inside the profile's own view tree, by id name. A view that matches is
     * not searched further, so a container makes the entries for its children redundant: those
     * only act if the container's name stops resolving.
     *
     * Deliberately NOT here:
     * - {@code swipeable_tab_view_pager}: in the dumps that is the pager of the app's MAIN tabs,
     *   an ancestor of every screen. It is a ceiling below, never a target.
     * - {@code profile_tab_icon_view}: one per tab, all inside profile_tab_layout.
     * - anything inside the pager (rows, cells): those views are recycled, and a recycled view
     *   set to GONE could turn up in another list.
     */
    private static final String[] GRID_VIEW_CANDIDATES = {
            "profile_tabs_container", // UNCONFIRMED
            "profile_viewpager",      // UNCONFIRMED
            "profile_tab_layout",     // UNCONFIRMED (child of profile_tabs_container)
    };

    /** Id names of the profile fragment's root view: the scope everything is searched in. */
    private static final String[] ROOT_VIEW_CANDIDATES = {
            "user_detail_fragment", // UNCONFIRMED
    };

    /**
     * Second way to recognise a fragment's root view: androidx sets the fragment as a tag on
     * it, under this id. UNCONFIRMED that Instagram's build keeps the name.
     */
    private static final String FRAGMENT_TAG_KEY = "fragment_container_view_tag";

    /**
     * The walk up from the action bar stops, without a scope, at the first of these (and at
     * android.R.id.content). They hold MORE than one screen (the main tab pager, the container
     * a tab's screens are swapped in), so searching from one of them could reach the user's own
     * profile. UNCONFIRMED names, but a wrong one here only means the walk stops a level later.
     */
    static final String[] CEILING_VIEWS = {
            "layout_container_swipeable",
            "swipeable_nav_view_pager_inner_recycler_view",
            "swipeable_tab_view_pager",
            "layout_container_main",
            "layout_container_main_panel",
            "swipe_navigation_container",
            "action_bar_root",
    };

    // =========================================================================================

    private static final int MAX_ANCESTORS = 24;
    private static final int MAX_DEPTH = 48;
    private static final int MAX_VISITED = 8_000;
    private static final long DUMP_DELAY_MS = 3_000L;
    private static final int DUMP_LIMIT = 150;
    /** A logcat line is cut at about 4000 characters. */
    private static final int DUMP_LINE_LENGTH = 3_000;
    /**
     * The first entries of GRID_VIEW_CANDIDATES that a profile is expected to have: the tab
     * strip container and the pager. If one of them never turns up, the names are off.
     */
    private static final int EXPECTED_CANDIDATES = 2;

    /** Main thread only, like everything below. */
    private static Names names;

    /**
     * One watch per profile view. The values are weak because a watch refers to its view; the
     * view itself keeps the watch alive, through the attach listener registered on it.
     */
    private static final Map<View, WeakReference<Watch>> WATCHES = new WeakHashMap<>();

    /** The watch of the most recently bound profile, when that profile was not the user's own. */
    private static WeakReference<Watch> current;

    private static String lastLine;

    private PostboxProfile() {
    }

    // ---- entry points --------------------------------------------------------------------------

    /**
     * Patch entry point: first instruction of piko's
     * {@code ActionBarPatch.userProfileActionBarButton(Activity, ViewGroup, UserSession, Object)},
     * with the same four arguments. Never throws.
     */
    @SuppressWarnings("unused")
    public static void onProfileBound(Activity activity, ViewGroup actionBar, UserSession session, Object user) {
        try {
            if (!PostboxBuild.profileGrid()) return;

            String selfId = null;
            try {
                if (session != null) selfId = session.getUserId();
            } catch (Throwable t) {
                Log.e(TAG, LOG + "cannot read the logged-in user id: " + t);
            }
            String profileId = null;
            boolean readFailed = false;
            UserData profile = null;
            try {
                if (user != null) {
                    profile = new UserData(user);
                    profileId = profile.getUserId();
                }
            } catch (Throwable t) {
                readFailed = true;
                Log.e(TAG, LOG + "cannot read the profile's user id (piko UserData.getUserId): " + t);
            }
            final Verdict verdict = PostboxProfileNet.classify(selfId, profileId, readFailed);

            // The network half: the session is the viewer's, whoever the profile belongs to.
            PostboxProfileNet.learnSelfId(selfId, activity == null ? null : activity.getClass().getName());
            if (verdict == Verdict.SELF) {
                try {
                    PostboxProfileNet.learnSelfName(profile.getUsername());
                } catch (Throwable ignored) {
                    // Only the by-name form of the grid request needs it.
                }
            }

            if (actionBar == null) {
                if (verdict == Verdict.SELF) {
                    // As in bind(): the own profile was bound last, nobody else speaks any more.
                    current = null;
                    PostboxProfileNet.setOtherInFront(false);
                }
                logOnce(LOG + "bind verdict=" + verdict + " without an action bar view, no view hide");
                return;
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                bind(actionBar, verdict);
            } else {
                final ViewGroup bar = actionBar;
                bar.post(() -> {
                    try {
                        bind(bar, verdict);
                    } catch (Throwable t) {
                        Log.e(TAG, LOG + "bind failed", t);
                    }
                });
            }
        } catch (Throwable t) {
            Log.e(TAG, LOG + "bind failed", t);
        }
    }

    /**
     * Called by {@link Postbox} for every activity before its onCreate. A restart of the
     * activity the profiles live in is how an account switch looks from here, see
     * {@link PostboxProfileNet#forgetSelfIfHost}. Never throws.
     */
    static void onActivityCreating(Activity activity) {
        try {
            if (activity == null) return;
            if (PostboxProfileNet.forgetSelfIfHost(activity.getClass().getName())) {
                Log.i(TAG, LOG + activity.getClass().getSimpleName()
                        + " is starting again: logged-in ids forgotten until the next profile bind");
            }
        } catch (Throwable t) {
            Log.w(TAG, LOG + "activity check: " + t);
        }
    }

    // ---- one bind ------------------------------------------------------------------------------

    private static void bind(ViewGroup actionBar, Verdict verdict) {
        Names resolved = resolveNames(actionBar);
        Scope scope = findScope(actionBar, resolved);
        // Without a scope nothing is hidden, but the bar itself still says whether this
        // profile is on screen, which is what the reels request rule goes by.
        View target = scope.root != null ? scope.root : actionBar;
        Watch watch = watchOf(target);

        String outcome;
        if (verdict == Verdict.SELF) {
            current = null;
            PostboxProfileNet.setOtherInFront(false);
            int restored = watch == null ? 0 : watch.release(target);
            outcome = restored == 0 ? "own profile, nothing touched" : "own profile, " + restored + " hidden view(s) restored";
        } else if (PostboxProfileNet.hidesGrid(verdict)) {
            if (watch == null) {
                watch = new Watch(target, scope.root != null, resolved);
                WATCHES.put(target, new WeakReference<>(watch));
                watch.start(target);
            }
            current = new WeakReference<>(watch);
            outcome = watch.onBind(target, resolved);
        } else {
            outcome = "cannot tell whose profile this is, left alone";
        }
        logOnce(LOG + "bind verdict=" + verdict + " scope=" + scope.description + " " + resolved.summary
                + " -> " + outcome);
    }

    private static Watch watchOf(View target) {
        WeakReference<Watch> reference = WATCHES.get(target);
        return reference == null ? null : reference.get();
    }

    private static boolean isCurrent(Watch watch) {
        WeakReference<Watch> reference = current;
        return reference != null && reference.get() == watch;
    }

    /** The same line twice in a row says nothing new; the action bar is rebuilt often. */
    private static void logOnce(String line) {
        if (line.equals(lastLine)) return;
        lastLine = line;
        Log.i(TAG, line);
    }

    // ---- names to ids --------------------------------------------------------------------------

    /** The id names above, resolved against this app's resources. */
    private static final class Names {
        final int[] grid;
        final int[] roots;
        final int[] ceilings;
        final int fragmentTag;
        final String summary;

        Names(int[] grid, int[] roots, int[] ceilings, int fragmentTag, String summary) {
            this.grid = grid;
            this.roots = roots;
            this.ceilings = ceilings;
            this.fragmentTag = fragmentTag;
            this.summary = summary;
        }
    }

    private static Names resolveNames(View view) {
        Names cached = names;
        if (cached != null) return cached;

        Resources resources = view.getResources();
        Set<String> packages = resourcePackagesOf(view);

        StringBuilder summary = new StringBuilder("ids[");
        int[] grid = resolveAll(resources, packages, GRID_VIEW_CANDIDATES, summary);
        int[] roots = resolveAll(resources, packages, ROOT_VIEW_CANDIDATES, summary);
        int fragmentTag = resolveAll(resources, packages, new String[] {FRAGMENT_TAG_KEY}, summary)[0];
        summary.append("packages=").append(packages).append(']');
        // Not worth a line each; they only bound the walk.
        int[] ceilings = resolveAll(resources, packages, CEILING_VIEWS, null);

        Names result = new Names(grid, roots, ceilings, fragmentTag, summary.toString());
        // Cache only something useful: a miss may just mean this view had no id to read the
        // package from, and the next bind is cheap.
        if (anyResolved(grid)) names = result;
        return result;
    }

    /**
     * The packages an id name is looked up in. The package the ids are filed under is the one
     * in the resource table. In a clone that may or may not be the renamed application id, so
     * it is read off a view that really has an id from that table (this one or an ancestor)
     * instead of being assumed (and never hardcoded). "Postbox surface lock" resolves its names
     * the same way.
     */
    static Set<String> resourcePackagesOf(View view) {
        Resources resources = view.getResources();
        Set<String> packages = new LinkedHashSet<>();
        View cursor = view;
        for (int level = 0; cursor != null && level < MAX_ANCESTORS; level++) {
            String owner = resourcePackageOf(resources, cursor.getId());
            if (owner != null) packages.add(owner);
            ViewParent parent = cursor.getParent();
            cursor = parent instanceof View ? (View) parent : null;
        }
        packages.add(view.getContext().getPackageName());
        return packages;
    }

    static int[] resolveAll(Resources resources, Set<String> packages, String[] idNames, StringBuilder summary) {
        int[] ids = new int[idNames.length];
        for (int index = 0; index < idNames.length; index++) {
            for (String owner : packages) {
                ids[index] = resources.getIdentifier(idNames[index], "id", owner);
                if (ids[index] != 0) break;
            }
            if (summary != null) {
                summary.append(idNames[index]).append('=')
                        .append(ids[index] == 0 ? "UNRESOLVED" : "0x" + Integer.toHexString(ids[index])).append(' ');
            }
        }
        return ids;
    }

    private static boolean anyResolved(int[] ids) {
        for (int id : ids) {
            if (id != 0) return true;
        }
        return false;
    }

    /** The resource-table package of an app-defined id, or null. */
    static String resourcePackageOf(Resources resources, int id) {
        // 0x01 is the framework's own table; View.NO_ID is -1.
        if (id == View.NO_ID || id == 0 || (id >>> 24) <= 0x01) return null;
        try {
            return resources.getResourcePackageName(id);
        } catch (Throwable notAResource) {
            return null;
        }
    }

    static int indexOf(int[] ids, int id) {
        if (id == View.NO_ID || id == 0) return -1;
        for (int index = 0; index < ids.length; index++) {
            if (ids[index] == id) return index;
        }
        return -1;
    }

    static String describe(View view) {
        String name;
        int id = view.getId();
        if (id == View.NO_ID) {
            name = "(no id)";
        } else {
            try {
                name = view.getResources().getResourceEntryName(id);
            } catch (Throwable notAResource) {
                name = "0x" + Integer.toHexString(id);
            }
        }
        return name + ":" + view.getClass().getSimpleName();
    }

    // ---- the scope: the profile fragment's own view tree ---------------------------------------

    private static final class Scope {
        /** Null when no safe scope was found: then nothing is hidden. */
        final View root;
        final String description;

        Scope(View root, String description) {
            this.root = root;
            this.description = description;
        }
    }

    /**
     * Walks up from the action bar to the root view of the fragment it sits in, and no further.
     * The whole activity is never a scope: the user's own profile tab can be alive in the same
     * activity. Order at each ancestor: a known root name wins, then a ceiling ends the walk,
     * and only then does the fragment tag count (a ceiling may well be some other fragment's
     * root).
     */
    private static Scope findScope(View actionBar, Names resolved) {
        StringBuilder chain = new StringBuilder();
        ViewParent parent = actionBar.getParent();
        for (int level = 0; parent instanceof View && level < MAX_ANCESTORS; level++) {
            View ancestor = (View) parent;
            int id = ancestor.getId();
            if (indexOf(resolved.roots, id) >= 0) {
                return new Scope(ancestor, describe(ancestor) + " (by name)");
            }
            if (id == android.R.id.content || indexOf(resolved.ceilings, id) >= 0) {
                chain.append(describe(ancestor));
                return new Scope(null, "NONE, view hide OFF (reached " + describe(ancestor)
                        + " without finding the fragment root; ancestors: " + chain + ")");
            }
            if (resolved.fragmentTag != 0 && ancestor.getTag(resolved.fragmentTag) != null) {
                return new Scope(ancestor, describe(ancestor) + " (by fragment tag)");
            }
            chain.append(describe(ancestor)).append(" < ");
            parent = ancestor.getParent();
        }
        return new Scope(null, "NONE, view hide OFF (no fragment root above the action bar; ancestors: "
                + chain + (parent == null ? "detached" : "...") + ")");
    }

    // ---- one profile view ----------------------------------------------------------------------

    /**
     * Keeps the grid hidden inside one profile view for as long as that view lives, and keeps
     * {@link PostboxProfileNet#setOtherInFront} up to date for it.
     *
     * Listener lifetime: the attach listener sits on the view itself and dies with it. The
     * global-layout listener sits on the observer of the WINDOW (that is what a view hands out
     * once attached), which outlives the profile, so it is registered only while the view is
     * attached and removed in onViewDetachedFromWindow. Should that callback ever be missed,
     * the next layout pass finds the view detached and removes the listener then; the view
     * itself is only referred to weakly.
     */
    private static final class Watch implements ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
        private final WeakReference<View> target;
        /** False when no scope was found: the watch then only tracks whether it is on screen. */
        private final boolean hides;
        private int[] gridIds;
        private final int[] matches = new int[GRID_VIEW_CANDIDATES.length];
        /** Which candidates were found at least once, whatever their visibility. */
        private final boolean[] seen = new boolean[GRID_VIEW_CANDIDATES.length];
        private final ArrayList<View> found = new ArrayList<>();
        /** What this watch set to GONE, with the visibility it had. Weak keys. */
        private final Map<View, Integer> hidden = new WeakHashMap<>();
        private final RehideBudget budget = new RehideBudget();
        private final Rect visibleRect = new Rect();

        private ViewTreeObserver registeredOn;
        private boolean released;
        private boolean dumpPosted;
        private boolean recheckPosted;
        private boolean ambiguityLogged;
        private boolean throttleLogged;
        private int visited;

        Watch(View target, boolean hides, Names resolved) {
            this.target = new WeakReference<>(target);
            this.hides = hides;
            this.gridIds = resolved.grid;
        }

        void start(View view) {
            view.addOnAttachStateChangeListener(this);
            if (view.isAttachedToWindow()) register(view);
        }

        private void register(View view) {
            unregister();
            ViewTreeObserver observer = view.getViewTreeObserver();
            if (observer != null && observer.isAlive()) {
                observer.addOnGlobalLayoutListener(this);
                registeredOn = observer;
            }
        }

        private void unregister() {
            ViewTreeObserver observer = registeredOn;
            registeredOn = null;
            try {
                // Exactly the observer it was added to. A dead one throws and has nothing to remove.
                if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
            } catch (Throwable ignored) {
            }
        }

        /** The profile was bound (again) as not the user's own. Returns the log text. */
        String onBind(View view, Names resolved) {
            gridIds = resolved.grid;
            budget.reset();
            if (view.isAttachedToWindow() && registeredOn == null) register(view);
            List<String> newly = hides ? hidePass(view) : null;
            updateFront(view);
            if (!hides) return "no view hide, tracking the action bar only";
            if (!expectedSeen() && !dumpPosted) {
                dumpPosted = true;
                view.postDelayed(this::dumpIfNamesAreOff, DUMP_DELAY_MS);
            }
            return "hidden now=" + newly + " hidden in all=" + hidden.size();
        }

        @Override
        public void onGlobalLayout() {
            try {
                View view = target.get();
                if (released || view == null || !view.isAttachedToWindow()) {
                    unregister();
                    setFront(false);
                    return;
                }
                // A profile nobody can see needs no work; showing it again lays out again.
                if (hides && view.isShown()) {
                    List<String> newly = hidePass(view);
                    if (!newly.isEmpty()) Log.i(TAG, LOG + "hidden after layout=" + newly);
                }
                updateFront(view);
            } catch (Throwable t) {
                Log.w(TAG, LOG + "layout pass: " + t);
            }
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            try {
                if (released) return;
                register(view);
                if (hides) hidePass(view);
                updateFront(view);
            } catch (Throwable t) {
                Log.w(TAG, LOG + "attach: " + t);
            }
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            try {
                unregister();
                setFront(false);
            } catch (Throwable t) {
                Log.w(TAG, LOG + "detach: " + t);
            }
        }

        /**
         * The same view turned out to be the user's own profile: stop, and put back what this
         * watch hid (and only that). Returns how many views were restored.
         */
        int release(View view) {
            released = true;
            unregister();
            view.removeOnAttachStateChangeListener(this);
            WATCHES.remove(view);
            int restored = 0;
            for (Map.Entry<View, Integer> entry : hidden.entrySet()) {
                View hiddenView = entry.getKey();
                // Only if it is still the way this watch left it.
                if (hiddenView != null && hiddenView.getVisibility() == View.GONE) {
                    hiddenView.setVisibility(entry.getValue());
                    restored++;
                }
            }
            hidden.clear();
            return restored;
        }

        // ---- hiding ----

        /**
         * One search of the profile's view tree. Sets every candidate that is not already GONE
         * to GONE and returns the descriptions of the ones hidden for the first time.
         *
         * No loop: a view that is GONE is not touched, so a pass that changes nothing requests
         * no layout, and the pass after a change finds nothing left to change.
         */
        private List<String> hidePass(View root) {
            // Runs on every layout pass of the window: no allocation unless something changes.
            List<String> newly = Collections.emptyList();
            Arrays.fill(matches, 0);
            found.clear();
            visited = 0;
            collect(root, 0);

            // Each of these exists once in a profile. Twice means the scope holds more than
            // one profile after all, and then it is not known which one is the user's own.
            for (int index = 0; index < matches.length; index++) {
                if (matches[index] > 1) {
                    if (!ambiguityLogged) {
                        ambiguityLogged = true;
                        Log.w(TAG, LOG + matches[index] + " views named " + GRID_VIEW_CANDIDATES[index] + " under "
                                + describe(root) + ": the scope is wider than one profile, nothing is hidden");
                    }
                    found.clear();
                    return newly;
                }
            }

            long now = SystemClock.elapsedRealtime();
            for (View view : found) {
                int visibility = view.getVisibility();
                if (visibility == View.GONE) continue;
                boolean again = hidden.containsKey(view);
                if (again && !budget.take(now)) {
                    throttled(root, view);
                    continue;
                }
                if (!again) {
                    hidden.put(view, visibility);
                    if (newly.isEmpty()) newly = new ArrayList<>();
                    newly.add(describe(view));
                }
                view.setVisibility(View.GONE);
            }
            found.clear();
            return newly;
        }

        private void collect(View view, int depth) {
            if (++visited > MAX_VISITED) return;
            int match = indexOf(gridIds, view.getId());
            if (match >= 0) {
                matches[match]++;
                seen[match] = true;
                found.add(view);
                return; // whatever is inside goes with it
            }
            if (depth >= MAX_DEPTH || !(view instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) view;
            for (int index = 0, count = group.getChildCount(); index < count; index++) {
                View child = group.getChildAt(index);
                if (child != null) collect(child, depth + 1);
            }
        }

        /** Something keeps showing a view this watch hid. Leave it for now, look again later. */
        private void throttled(View root, View view) {
            if (!throttleLogged) {
                throttleLogged = true;
                Log.w(TAG, LOG + describe(view) + " keeps being shown again; re-hiding at most "
                        + RehideBudget.BURST + " times per " + RehideBudget.WINDOW_MS + " ms");
            }
            if (recheckPosted) return;
            recheckPosted = true;
            root.postDelayed(() -> {
                recheckPosted = false;
                onGlobalLayout();
            }, RehideBudget.WINDOW_MS);
        }

        // ---- is it on screen ----

        private void updateFront(View view) {
            setFront(view.isAttachedToWindow() && view.isShown() && view.getGlobalVisibleRect(visibleRect));
        }

        /** Only the most recently bound profile speaks for the reels request rule. */
        private void setFront(boolean front) {
            if (isCurrent(this)) PostboxProfileNet.setOtherInFront(front);
        }

        // ---- finding the right names ----

        private boolean expectedSeen() {
            for (int index = 0; index < EXPECTED_CANDIDATES && index < seen.length; index++) {
                if (!seen[index]) return false;
            }
            return true;
        }

        /**
         * A not-own profile where the tab strip or the pager never turned up: a name is wrong
         * for this build (or this profile has no grid at all, e.g. a private account). Lists the
         * id names that ARE under the scope, once per profile view, so the names can be fixed.
         */
        private void dumpIfNamesAreOff() {
            try {
                View view = target.get();
                if (released || expectedSeen() || view == null || !view.isAttachedToWindow()) return;
                StringBuilder missing = new StringBuilder();
                for (int index = 0; index < EXPECTED_CANDIDATES && index < seen.length; index++) {
                    if (!seen[index]) missing.append(GRID_VIEW_CANDIDATES[index]).append(' ');
                }
                Set<String> present = new LinkedHashSet<>();
                listIds(view, 0, present);
                Log.w(TAG, LOG + "NOT FOUND under " + describe(view) + " after " + DUMP_DELAY_MS + " ms: " + missing
                        + "- that part of the grid is NOT hidden by view. Ids present (first " + DUMP_LIMIT + ") follow");
                StringBuilder line = new StringBuilder();
                for (String name : present) {
                    if (line.length() + name.length() > DUMP_LINE_LENGTH) {
                        Log.w(TAG, LOG + "ids: " + line);
                        line.setLength(0);
                    }
                    line.append(name).append(' ');
                }
                if (line.length() > 0) Log.w(TAG, LOG + "ids: " + line);
            } catch (Throwable t) {
                Log.w(TAG, LOG + "id dump: " + t);
            }
        }

        private void listIds(View view, int depth, Set<String> present) {
            if (present.size() >= DUMP_LIMIT) return;
            if (view.getId() != View.NO_ID) present.add(describe(view));
            if (depth >= MAX_DEPTH || !(view instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) view;
            for (int index = 0, count = group.getChildCount(); index < count; index++) {
                View child = group.getChildAt(index);
                if (child != null) listIds(child, depth + 1, present);
            }
        }
    }
}
