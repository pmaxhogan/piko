package app.morphe.extension.instagram.patches.postbox;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Postbox (Vantage fork only): the pure half of "Postbox profile grid" (D8).
 *
 * Other people's profiles keep their header, bio, highlights and follower lists but lose the
 * posts and reels grid; the user's own profile keeps everything. {@link PostboxProfile} hides
 * the grid views. This class is the second layer, for when the view hide misses: it decides
 * which requests fill a grid and whether one of them is for somebody else, so
 * {@link PostboxNet#interceptUri} can fail it and the grid has nothing to show.
 *
 * No Android types here. What it holds is small and thread safe, because it is written on the
 * main thread (a profile's action bar is bound, a view is laid out) and read on whatever thread
 * Instagram starts a request from. Requests never touch a view.
 *
 * THE ENDPOINTS BELOW ARE UNCONFIRMED on the Instagram version piko supports: nothing here has
 * been run against a real app. They are the long-standing private API paths. Confirm each with
 * a "Postbox network block" trace build ({@code adb logcat -s PostboxNet}) while opening someone
 * else's profile, and add or replace what the trace shows.
 */
final class PostboxProfileNet {
    /** Whose profile a bound action bar belongs to. */
    enum Verdict {
        /** The logged-in user's own profile: nothing is hidden. */
        SELF,
        /** Somebody else's: the grid is hidden. */
        OTHER,
        /**
         * The profile's user is not loaded yet (no user object, or one without an id), while
         * the logged-in id is known. Treated as somebody else's, see {@link #hidesGrid}.
         */
        NOT_LOADED,
        /** It could not be told (the logged-in id is missing, or reading an id failed). */
        UNKNOWN,
    }

    /** What a request path is, as far as the grid is concerned. */
    enum Kind {
        NONE,
        /** {@code /feed/user/<id>/}: the posts grid of one account. */
        USER_FEED,
        /** {@code /feed/user/<username>/username/}: the same, asked for by name. */
        USER_FEED_BY_NAME,
        /** {@code /usertags/<id>/feed/}: the "tagged" grid of one account. */
        USER_TAGS,
        /** {@code /clips/user/}: the reels grid. A POST; whose reels is in the body, not here. */
        USER_CLIPS,
    }

    private static final String FEED_USER = "/feed/user/";
    private static final String USERTAGS = "/usertags/";
    private static final String CLIPS_USER = "/clips/user/";
    private static final String CLIPS_USER_NO_SLASH = "/clips/user";
    private static final String BY_NAME_SUFFIX = "username";
    private static final String TAGS_SUFFIX = "feed";

    /** A path with its grid meaning and the account it names (an id, or a lower-case username). */
    static final class Request {
        static final Request NONE = new Request(Kind.NONE, null);

        final Kind kind;
        final String target;

        private Request(Kind kind, String target) {
            this.kind = kind;
            this.target = target;
        }

        /**
         * Pure. Shapes are matched exactly, so a longer sibling is never taken for a grid:
         * {@code /feed/user/<id>/story/} is a story, {@code /feed/reels_tray/} the stories tray,
         * {@code /users/<id>/info/} the profile header, {@code /friendships/<id>/followers/} a
         * follower list. Anything not recognised is {@link Kind#NONE} and is left alone.
         */
        static Request of(String path) {
            if (path == null) return NONE;

            String[] feed = segmentsAfter(path, FEED_USER);
            if (feed != null) {
                if (feed.length == 1 && isId(feed[0])) return new Request(Kind.USER_FEED, feed[0]);
                if (feed.length == 2 && BY_NAME_SUFFIX.equals(feed[1]) && !feed[0].isEmpty()) {
                    return new Request(Kind.USER_FEED_BY_NAME, normalizeName(feed[0]));
                }
                return NONE;
            }
            String[] tags = segmentsAfter(path, USERTAGS);
            if (tags != null) {
                return tags.length == 2 && isId(tags[0]) && TAGS_SUFFIX.equals(tags[1])
                        ? new Request(Kind.USER_TAGS, tags[0])
                        : NONE;
            }
            // Only the bare endpoint. /clips/user/<something>/ is some other call.
            if (path.endsWith(CLIPS_USER) || path.endsWith(CLIPS_USER_NO_SLASH)) {
                return new Request(Kind.USER_CLIPS, null);
            }
            return NONE;
        }
    }

    /**
     * The path segments after the first occurrence of {@code marker} (which ends in a slash),
     * without the empty one a trailing slash leaves. Null when the marker is not in the path.
     */
    private static String[] segmentsAfter(String path, String marker) {
        int at = path.indexOf(marker);
        if (at < 0) return null;
        String rest = path.substring(at + marker.length());
        if (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        if (rest.isEmpty()) return new String[0];
        // -1: keep empty segments, so "a//b" is three segments and matches nothing.
        return rest.split("/", -1);
    }

    /** Account ids are decimal numbers. */
    static boolean isId(String value) {
        if (value == null || value.isEmpty() || value.length() > 24) return false;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String normalizeName(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    // ---- whose profile -------------------------------------------------------------------------

    /**
     * Pure: whose profile was bound.
     *
     * @param selfId     the logged-in session's user id
     * @param profileId  the id of the user the profile shows
     * @param readFailed reading the profile's id threw (the reflection behind piko's UserData)
     */
    static Verdict classify(String selfId, String profileId, boolean readFailed) {
        // Both of these are failures that would repeat on EVERY profile, the user's own
        // included. Calling them "somebody else" would hide the user's own grid for good.
        if (readFailed || isBlank(selfId)) return Verdict.UNKNOWN;
        // This one is different: only a profile that still has to be fetched has no user yet,
        // and the logged-in user's own account is in memory from login.
        if (isBlank(profileId)) return Verdict.NOT_LOADED;
        return selfId.trim().equals(profileId.trim()) ? Verdict.SELF : Verdict.OTHER;
    }

    /**
     * Pure: whether the grid is hidden for this verdict. NOT_LOADED fails closed (hidden): it
     * cannot be the user's own profile in the normal case, and the action bar is bound again
     * once the user has loaded, at which point a SELF verdict puts everything back. UNKNOWN
     * fails open, for the reason given in {@link #classify}.
     */
    static boolean hidesGrid(Verdict verdict) {
        return verdict == Verdict.OTHER || verdict == Verdict.NOT_LOADED;
    }

    // ---- which requests ------------------------------------------------------------------------

    /**
     * Pure: whether a grid request is blocked.
     *
     * The unknown rule: while no logged-in id is known, NOTHING that depends on one is blocked.
     * Blocking then would also empty the user's own grid, and that is the worse mistake: the
     * view hide still covers somebody else's profile, nothing covers the user's own. The same
     * goes for the by-name form while no own username is known.
     *
     * @param selfIds      ids of the logged-in sessions seen so far
     * @param selfNames    their usernames, lower case, as far as known
     * @param otherInFront the most recently bound profile is not the user's own and is still
     *                     on screen (the only handle there is on {@link Kind#USER_CLIPS})
     */
    static boolean isBlocked(Request request, Set<String> selfIds, Set<String> selfNames, boolean otherInFront) {
        if (request == null) return false;
        switch (request.kind) {
            case USER_FEED:
            case USER_TAGS:
                return !selfIds.isEmpty() && !selfIds.contains(request.target);
            case USER_FEED_BY_NAME:
                return !selfIds.isEmpty() && !selfNames.isEmpty() && !selfNames.contains(request.target);
            case USER_CLIPS:
                return otherInFront;
            default:
                return false;
        }
    }

    // ---- what is known right now ---------------------------------------------------------------

    /**
     * Ids of the logged-in sessions, learned from the UserSession every time a profile's action
     * bar is bound (anybody's profile: the session is the viewer's). A set, because Instagram
     * keeps several accounts logged in and each has its own session.
     */
    private static final Set<String> SELF_IDS = ConcurrentHashMap.newKeySet();

    /** Their usernames, lower case, learned when the user's own profile is bound. */
    private static final Set<String> SELF_NAMES = ConcurrentHashMap.newKeySet();

    /** Class names of the activities profiles were bound in (the ones that host profiles). */
    private static final Set<String> HOST_ACTIVITIES = ConcurrentHashMap.newKeySet();

    /** See {@link #isBlocked}. Written on the main thread only. */
    private static volatile boolean otherInFront;

    private PostboxProfileNet() {
    }

    /** A profile was bound under this session, inside an activity of this class. */
    static void learnSelfId(String selfId, String activityClassName) {
        if (isBlank(selfId)) return;
        SELF_IDS.add(selfId.trim());
        if (activityClassName != null) HOST_ACTIVITIES.add(activityClassName);
    }

    static void learnSelfName(String username) {
        if (!isBlank(username)) SELF_NAMES.add(normalizeName(username));
    }

    /**
     * An activity is being created. If it is of a class profiles were bound in, an activity
     * that hosts profiles is starting over, which is what switching accounts (and logging out
     * and in as somebody else) does: forget everything, so the unknown rule applies until the
     * next profile bind names the session again. Without this an id left over from the previous
     * account would get the new account's own grid blocked. Forgetting too often only costs
     * some blocking until that next bind; it can never block more.
     *
     * @return whether anything was forgotten
     */
    static boolean forgetSelfIfHost(String activityClassName) {
        if (activityClassName == null || !HOST_ACTIVITIES.contains(activityClassName)) return false;
        boolean known = !SELF_IDS.isEmpty() || !SELF_NAMES.isEmpty();
        SELF_IDS.clear();
        SELF_NAMES.clear();
        return known;
    }

    static void setOtherInFront(boolean value) {
        otherInFront = value;
    }

    static boolean isOtherInFront() {
        return otherInFront;
    }

    static int knownSelfIds() {
        return SELF_IDS.size();
    }

    /** Whether this request path is blocked, given what is known right now. Never throws. */
    static boolean isBlockedNow(String path) {
        Request request = Request.of(path);
        if (request.kind == Kind.NONE) return false;
        return isBlocked(request, SELF_IDS, SELF_NAMES, otherInFront);
    }

    /** Tests only. */
    static void reset() {
        SELF_IDS.clear();
        SELF_NAMES.clear();
        HOST_ACTIVITIES.clear();
        otherInFront = false;
    }

    // ---- the re-hide budget --------------------------------------------------------------------

    /**
     * Guards the view hide against a fight. Hiding a view that is visible costs one layout
     * pass, and a view that is already gone is never touched, so on its own the hider cannot
     * loop. But if Instagram put a hidden view back on every layout pass, hiding it again each
     * time would keep the window laying out forever. So a view the hider has ALREADY hidden
     * once may be hidden again at most {@link #BURST} times per {@link #WINDOW_MS}; past that it
     * is left for the rest of the window and re-checked when the window ends.
     *
     * Pure: the clock is passed in. Not thread safe; the hider lives on the main thread.
     */
    static final class RehideBudget {
        static final int BURST = 6;
        static final long WINDOW_MS = 1_000L;

        private long windowStartMs = Postbox.NEVER;
        private int used;

        /** Takes one re-hide from the budget; false when the window's budget is spent. */
        boolean take(long nowMs) {
            if (Postbox.intervalElapsed(nowMs, windowStartMs, WINDOW_MS)) {
                windowStartMs = nowMs;
                used = 0;
            }
            if (used >= BURST) return false;
            used++;
            return true;
        }

        /** A new bind starts a fresh budget. */
        void reset() {
            windowStartMs = Postbox.NEVER;
            used = 0;
        }
    }
}
