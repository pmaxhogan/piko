package app.morphe.extension.instagram.patches.postbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.URI;

import org.junit.Test;

import app.morphe.extension.instagram.constants.Constants;

/** The network block list (D4, D7, L2) and the parser-level drop, as pure functions. */
public class PostboxNetTest {
    /** Paths as Tigon requests carry them; each must be blocked. */
    private static final String[] BLOCKED = {
            // home feed (D4)
            "/api/v1/feed/timeline/",
            "/feed/timeline/",
            // Explore and the Search tab landing
            "/api/v1/discover/topical_explore/",
            "/api/v1/discover/topical_explore_stream/",
            "/api/v1/fbsearch/recent_searches/",
            "/api/v1/fbsearch/top_serp/",
            "/api/v1/fbsearch/nullstate_dynamic_sections/",
            // the search results page (a hashtag tapped in a caption lands on it)
            "/api/v1/fbsearch/top_serp_stream/",
            "/api/v1/fbsearch/account_serp/",
            "/api/v1/fbsearch/non_profiled_serp/",
            // Reels tab
            "/api/v1/clips/home/",
            "/api/v1/clips/discover/",
            "/api/v1/clips/discover/stream/",
            "/api/v1/clips/get_blend_medias/",
            // the reels grids of an audio, a place, a hashtag and a keyword
            "/api/v1/clips/music/",
            "/api/v1/clips/audio_page_chain_clips/",
            "/api/v1/clips/locations/213385402/",
            "/api/v1/clips/tags/sunset/",
            "/api/v1/clips/keyword/",
            // the Top and Recent grids of a place page
            "/api/v1/locations/213385402/sections/",
            // chaining below a post or reel (D7)
            "/api/v1/discover/chaining/",
            "/api/v1/discover/chaining",
            "/api/v1/discover/chaining_experience/",
            "/api/v1/discover/ayml/",
            "/api/v1/discover/sectioned_ayml/",
            "/api/v1/clips/chaining/",
            "/api/v1/clips/chaining_media/",
            "/api/v1/media/3141592653589793238/chaining/",
            "/api/v1/media/3141592653589793238_25025320/chaining_experience/",
            "/api/v1/media/chaining/",
            "/api/v1/feed/injected_reels_media/",
            "/api/v1/feed/injected_reels_media",
    };

    /** Must NOT be blocked: stories, DMs, single items, profiles, people search, accounts. */
    private static final String[] NOT_BLOCKED = {
            // stories tray and playback (the tray shares the /feed/ prefix with the timeline)
            "/api/v1/feed/reels_tray/",
            "/api/v1/feed/reels_media/",
            "/api/v1/feed/reels_media_stream/",
            "/api/v1/feed/user/25025320/story/",
            "/api/v1/feed/get_latest_reel_media/",
            "/api/v1/media/seen/",
            "/api/v2/media/seen/",
            "/api/v1/highlights/25025320/highlights_tray/",
            // direct
            "/api/v1/direct_v2/inbox/",
            "/api/v1/direct_v2/threads/340282366841710300949128137443944319108/",
            "/api/v1/direct_v2/pending_inbox/",
            "/api/v1/direct_v2/ranked_recipients/",
            "/api/v1/direct_v2/threads/broadcast/text/",
            // recipient and people search used by DMs and story tagging
            "/api/v1/users/search/",
            "/api/v1/fbsearch/topsearch_flat/",
            "/api/v1/fbsearch/ig_typeahead/",
            "/api/v1/fbsearch/keyword_typeahead/",
            "/api/v1/fbsearch/typeahead_stream/",
            "/api/v1/fbsearch/places/",
            "/api/v1/tags/search/",
            "/api/v1/location_search/",
            // a place's name and address, and its own story: only its post grids are blocked
            "/api/v1/locations/213385402/info/",
            "/api/v1/locations/213385402/location_info/",
            "/api/v1/locations/213385402/story/",
            "/api/v1/locations/sections/",
            // a single post or reel, and its comments
            "/api/v1/media/3141592653589793238/info/",
            "/api/v1/media/3141592653589793238_25025320/info/",
            "/api/v1/media/3141592653589793238/comments/",
            "/api/v1/media/3141592653589793238/comments/17900000000000000/child_comments/",
            "/api/v1/media/3141592653589793238/likers/",
            "/api/v1/media/3141592653589793238/like/",
            "/api/v1/clips/item/",
            "/api/v1/clips/items/",
            "/api/v1/clips/direct_thread_clips/",
            "/api/v1/clips/user/",
            "/api/v1/media/configure_to_story/",
            "/api/v1/media/configure/",
            "/api/v1/media/upload_finish/",
            // profiles
            "/api/v1/users/25025320/info/",
            "/api/v1/users/web_profile_info/",
            "/api/v1/feed/user/25025320/",
            "/api/v1/friendships/25025320/followers/",
            "/api/v1/friendships/25025320/following/",
            "/api/v1/friendships/show/25025320/",
            // notifications (the heart)
            "/api/v1/news/inbox/",
            // accounts, login, 2FA, settings
            "/api/v1/accounts/login/",
            "/api/v1/accounts/two_factor_login/",
            "/api/v1/accounts/logout/",
            "/api/v1/accounts/current_user/",
            "/api/v1/accounts/edit_profile/",
            "/api/v1/accounts/send_password_reset/",
            "/api/v1/challenge/",
            "/api/v1/launcher/mobileconfig/",
            "/api/v1/qe/sync/",
            // near misses of blocked names
            "/api/v1/discover/",
            "/api/v1/clips/",
            "/api/v1/feed/",
            "/chaining/media/",
            "/",
            "",
    };

    @Test
    public void blockedPathsAreBlocked() {
        for (String path : BLOCKED) {
            assertTrue("should block " + path, PostboxNet.isBlockedPath(path));
        }
    }

    @Test
    public void everythingElseIsLeftAlone() {
        for (String path : NOT_BLOCKED) {
            assertFalse("must not block " + path, PostboxNet.isBlockedPath(path));
        }
        assertFalse(PostboxNet.isBlockedPath(null));
    }

    /** D4 in one place: the feed is blocked, the stories tray that sits on the same tab is not. */
    @Test
    public void homeFeedIsBlockedButTheStoriesTrayIsNot() {
        assertTrue(PostboxNet.isBlockedPath("/api/v1/feed/timeline/"));
        assertFalse(PostboxNet.isBlockedPath("/api/v1/feed/reels_tray/"));
    }

    /** The cases the task names explicitly as off limits. */
    @Test
    public void namedMustNotBlockEndpoints() {
        assertFalse(PostboxNet.isBlockedPath("/api/v1/direct_v2/ranked_recipients/"));
        assertFalse(PostboxNet.isBlockedPath("/api/v1/users/search/"));
        assertFalse(PostboxNet.isBlockedPath("/api/v1/media/123/info/"));
        assertFalse(PostboxNet.isBlockedPath("/api/v1/media/123/comments/"));
        assertFalse(PostboxNet.isBlockedPath("/api/v1/clips/item/"));
    }

    /** Matching is on the path, exactly as it comes out of java.net.URI: never host or query. */
    @Test
    public void matchesThePathOfARealRequestUri() throws Exception {
        URI feed = new URI("https://i.instagram.com/api/v1/feed/timeline/?reason=cold_start_fetch");
        assertTrue(PostboxNet.isBlockedPath(feed.getPath()));
        URI tray = new URI("https://i.instagram.com/api/v1/feed/reels_tray/?next=/feed/timeline/");
        assertFalse(PostboxNet.isBlockedPath(tray.getPath()));
        URI chaining = new URI("https://i.instagram.com/api/v1/media/42/info/?from=chaining");
        assertFalse(PostboxNet.isBlockedPath(chaining.getPath()));
    }

    /**
     * L2 at the network: what fills the search results, audio, hashtag and place pages is
     * blocked; what people search, typeahead and a single reel need is not.
     */
    @Test
    public void taggedSurfacesAreBlockedButTypeaheadAndSingleReelsAreNot() {
        assertTrue(PostboxNet.isBlockedPath("/api/v1/fbsearch/top_serp/"));
        assertTrue(PostboxNet.isBlockedPath("/api/v1/fbsearch/top_serp_stream/"));
        assertTrue(PostboxNet.isBlockedPath("/api/v1/fbsearch/account_serp/"));
        assertTrue(PostboxNet.isBlockedPath("/api/v1/fbsearch/non_profiled_serp/"));
        assertTrue(PostboxNet.isBlockedPath("/api/v1/clips/music/"));
        assertTrue(PostboxNet.isBlockedPath("/api/v1/clips/audio_page_chain_clips/"));
        assertTrue(PostboxNet.isBlockedPath("/api/v1/clips/locations/42/"));
        assertTrue(PostboxNet.isBlockedPath("/api/v1/clips/tags/tag/"));
        assertTrue(PostboxNet.isBlockedPath("/api/v1/clips/keyword/"));

        for (String path : new String[] {
                "/api/v1/fbsearch/ig_typeahead/",
                "/api/v1/fbsearch/keyword_typeahead/",
                "/api/v1/fbsearch/typeahead_stream/",
                "/api/v1/fbsearch/places/",
                "/api/v1/tags/search/",
                "/api/v1/location_search/",
                "/api/v1/clips/item/",
                "/api/v1/clips/items/",
                "/api/v1/clips/direct_thread_clips/",
                "/api/v1/clips/user/",
        }) {
            assertFalse("must not block " + path, PostboxNet.isBlockedPath(path));
        }
    }

    @Test
    public void locationSectionsNeedBothParts() {
        assertTrue(PostboxNet.isBlockedPath("/api/v1/locations/42/sections/"));
        // The place's own details, and place search while tagging a story, stay reachable.
        assertFalse(PostboxNet.isBlockedPath("/api/v1/locations/42/info/"));
        assertFalse(PostboxNet.isBlockedPath("/api/v1/locations/42/story/"));
        assertFalse(PostboxNet.isBlockedPath("/api/v1/location_search/"));
        // "/sections/" before "/locations/", or with nothing in between, is some other endpoint.
        assertFalse(PostboxNet.isBlockedPath("/api/v1/sections/locations/42/"));
        assertFalse(PostboxNet.isBlockedPath("/api/v1/locations/sections/"));
    }

    @Test
    public void mediaChainingNeedsBothParts() {
        assertTrue(PostboxNet.isBlockedPath("/api/v1/media/42/chaining/"));
        // "chaining" before "/media/" is some other endpoint.
        assertFalse(PostboxNet.isBlockedPath("/api/v1/chaining/media/42/"));
        assertFalse(PostboxNet.isBlockedPath("/api/v1/media/42/info/"));
    }

    // ---- parser keys ----

    @Test
    public void postboxParserKeysAreExactNames() {
        assertTrue(PostboxNet.isDroppedParserKey("ad4ad"));
        assertTrue(PostboxNet.isDroppedParserKey("vibes_in_feed_unit"));
        for (String key : new String[] {
                null, "", "ad", "ad4", "ad4ad_", "ad4ads", "AD4AD", " ad4ad", "ad4ad ", "xad4ad",
                "vibes", "vibes_in_feed", "vibes_in_feed_units", "Vibes_In_Feed_Unit",
                // real feed units that must keep parsing
                "media_or_ad", "media", "stories_tray", "end_of_feed_demarcator",
                // piko's own set is piko's business, not this one's
                "clips_netego", "stories_netego", "suggested_users", "in_feed_survey",
        }) {
            assertFalse("for [" + key + "]", PostboxNet.isDroppedParserKey(key));
        }
    }

    @Test
    public void droppedKeysBecomePikosInvalidToken() {
        assertEquals("piko", Constants.PIKO);
        assertEquals(Constants.PIKO, PostboxNet.replaceJsonParserKey("ad4ad", true));
        assertEquals(Constants.PIKO, PostboxNet.replaceJsonParserKey("vibes_in_feed_unit", true));
        // The token itself is not a key anything drops, so piko's own check passes it through.
        assertFalse(PostboxNet.isDroppedParserKey(Constants.PIKO));
    }

    @Test
    public void otherKeysComeBackAsTheSameObject() {
        String key = "media_or_ad";
        assertSame(key, PostboxNet.replaceJsonParserKey(key, true));
        assertNull(PostboxNet.replaceJsonParserKey(null, true));
    }

    @Test
    public void nothingIsReplacedWhileTheFeatureIsOff() {
        assertEquals("ad4ad", PostboxNet.replaceJsonParserKey("ad4ad", false));
        // The public entry points read PostboxBuild, which is all-off in source.
        assertEquals("ad4ad", PostboxNet.replaceJsonParserKey("ad4ad"));
        assertEquals("vibes_in_feed_unit", PostboxNet.replaceJsonParserKey("vibes_in_feed_unit"));
    }

    @Test
    public void interceptUriDoesNothingWhileTheFeatureIsOff() throws Exception {
        PostboxNet.interceptUri(new URI("https://i.instagram.com/api/v1/feed/timeline/"));
        PostboxNet.interceptUri(null);
    }
}
