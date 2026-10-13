package app.morphe.extension.instagram.patches.postbox;

import android.util.Log;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import app.morphe.extension.instagram.constants.Constants;

/**
 * Postbox (Vantage fork only): the runtime half of "Postbox network block" (D4, D7).
 *
 * Two seams, both in piko's own extension, both prepended to at patch time:
 *
 * - {@link #interceptUri(URI)} runs first thing in {@code Links.interceptUri(URI)}, which piko
 *   calls for every Tigon request. Throwing IOException there fails that one request the way a
 *   dropped connection would, so the surface behind it renders empty: the home feed (D4), the
 *   Explore grid, the Reels tab, the search results, audio, hashtag and place pages (L2), and
 *   everything that would page in below an opened post or reel (D7). This is the layer that
 *   still holds when a route into one of those surfaces was missed.
 * - {@link #replaceJsonParserKey(String)} runs first thing in {@code Block.replaceJsonParserKey},
 *   piko's hook on the feed-item parser, and gives two more unit types an unknown name so the
 *   parser drops them.
 *
 * Nothing here reads a preference. The matchers are pure and unit tested (PostboxNetTest).
 *
 * "Postbox profile grid" adds its own matcher to the first seam: {@link PostboxProfileNet}, which
 * blocks the requests that fill another account's posts and reels grid. It is a separate class
 * because, unlike the list here, its answer depends on who is logged in.
 */
public final class PostboxNet {
    /** Logcat tag of the request trace (every path), when the build has it on. */
    static final String TRACE_TAG = "PostboxNet";

    /** Logcat tag of the trace lines for requests that were blocked. */
    static final String BLOCK_TAG = "PostboxNetBlock";

    /**
     * Blocked when the request path contains one of these, the way piko's own list matches.
     * Trailing slashes are deliberate where a longer sibling must stay reachable.
     */
    private static final String[] BLOCKED_PATHS = {
            // Home feed (D4). The stories tray is /feed/reels_tray/ and is NOT this.
            "/feed/timeline/",
            // Explore and the Search tab's landing page.
            "/discover/topical_explore",
            "/fbsearch/recent_searches/",
            "/fbsearch/nullstate_dynamic_sections/",
            // The search results page, which a tap on a hashtag in a caption opens as well.
            // No trailing slash on the first: it is /fbsearch/top_serp/ and .../top_serp_stream/.
            // The typeahead endpoints (people search, caption and sticker typeahead) are not these.
            "/fbsearch/top_serp",
            "/fbsearch/account_serp/",
            "/fbsearch/non_profiled_serp/",
            // Reels tab.
            "/clips/home/",
            "/clips/discover",
            "/clips/get_blend_medias/",
            // The reels grids behind an audio, a place, a hashtag and a keyword. A single reel is
            // /clips/item/ or /clips/items/, the reels of a chat /clips/direct_thread_clips/.
            "/clips/music/",
            "/clips/audio_page_chain_clips/",
            "/clips/locations/",
            "/clips/tags/",
            "/clips/keyword/",
            // What pages in below or after a single post or reel (D7), and suggested accounts.
            // Without a trailing slash, like piko's own entries for the first two.
            "/discover/chaining",
            "/discover/ayml",
            "/discover/sectioned_ayml",
            "/clips/chaining",
            "/feed/injected_reels_media",
    };

    /**
     * Blocked when the path contains the first part and, anywhere after it, the second: an id
     * sits between the two.
     *
     * - /media/<id>/chaining...: "more posts like this" for one media id.
     * - /locations/<id>/sections/: the Top and Recent grids of a place page. A bare "/locations/"
     *   would also take the place's name and address and the place's own story, and
     *   /location_search/ (tagging a place) is a different endpoint altogether.
     */
    private static final String[][] BLOCKED_PAIRS = {
            {"/media/", "chaining"},
            {"/locations/", "/sections/"},
    };

    /**
     * Feed unit types dropped at the parser on top of piko's own set (exact names, no prefixes):
     * the "ad4ad" ad unit and the "Vibes" unit.
     */
    private static final Set<String> DROPPED_PARSER_KEYS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("ad4ad", "vibes_in_feed_unit")));

    private PostboxNet() {
    }

    /** Pure: whether a request with this URI path is blocked. */
    static boolean isBlockedPath(String path) {
        if (path == null) return false;
        for (String blocked : BLOCKED_PATHS) {
            if (path.contains(blocked)) return true;
        }
        for (String[] pair : BLOCKED_PAIRS) {
            int first = path.indexOf(pair[0]);
            if (first >= 0 && path.indexOf(pair[1], first + pair[0].length()) >= 0) return true;
        }
        return false;
    }

    /** Pure: whether a feed unit with this type key is dropped. */
    static boolean isDroppedParserKey(String key) {
        return key != null && DROPPED_PARSER_KEYS.contains(key);
    }

    /** Pure: the key the parser should see. */
    static String replaceJsonParserKey(String key, boolean enabled) {
        // piko's own "no such key" token: the parser finds no case for it and skips the unit.
        return enabled && isDroppedParserKey(key) ? Constants.PIKO : key;
    }

    /**
     * Patch entry point, called at the top of {@code Links.interceptUri(URI)} for every request.
     *
     * @throws IOException for a blocked path, and for nothing else. The caller of piko's
     *                     interceptUri already treats that as a failed request.
     */
    @SuppressWarnings("unused")
    public static void interceptUri(URI uri) throws IOException {
        boolean block = false;
        try {
            if (!PostboxBuild.networkBlock() || uri == null) return;
            String path = uri.getPath();
            if (path == null) return;
            block = isBlockedPath(path);
            // feature: Postbox profile grid (D8). Same hook, so piko's Links.interceptUri keeps
            // exactly one Postbox call in front of it.
            if (!block && PostboxBuild.profileGrid() && PostboxProfileNet.isBlockedNow(path)) {
                block = true;
                // The kind only: the path names another account.
                Log.i(Postbox.TAG, "profile grid: blocked " + PostboxProfileNet.Request.of(path).kind);
            }
            if (PostboxBuild.traceNetwork()) {
                // Path only: the host says nothing new and the query carries ids and tokens.
                Log.i(TRACE_TAG, path);
                if (block) Log.i(BLOCK_TAG, path);
            }
        } catch (Throwable ignored) {
            // A request is blocked only if the matcher said so. Anything that went wrong before
            // that leaves it alone: failing closed here would fail every request the app makes.
        }
        if (block) throw new IOException("Postbox: blocked");
    }

    /**
     * Patch entry point, called at the top of {@code Block.replaceJsonParserKey(String)} with
     * the type key of each feed unit; its result replaces the key piko then looks at.
     */
    @SuppressWarnings("unused")
    public static String replaceJsonParserKey(String key) {
        try {
            return replaceJsonParserKey(key, PostboxBuild.networkBlock());
        } catch (Throwable t) {
            return key;
        }
    }
}
