package app.morphe.extension.instagram.patches.postbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/** The pure parts of the surface lock (L2, D6): which views mean a bounce, and the throttle. */
public class PostboxSurfaceTest {
    @Test
    public void theThreeForbiddenPagesAreRecognised() {
        // A hashtag tapped in a caption, a post's audio label, a post's location > "See more".
        assertEquals("serp", PostboxSurface.forbiddenPage("serp_journey_header_container"));
        assertEquals("audio", PostboxSurface.forbiddenPage("clips_pivot_page_fragment"));
        assertEquals("place", PostboxSurface.forbiddenPage("media_map_bottom_sheet_container"));
        assertEquals("place", PostboxSurface.forbiddenPage("location_detail_action_bar"));
    }

    /** Ids seen on the screens that must keep working. None of them may ever mean a bounce. */
    @Test
    public void allowedScreensAreNeverBounced() {
        for (String idName : new String[] {
                // the friend map, which is also where "See location" lands first
                "friend_map_friends_content_container", "map_container", "map_buttons_overlay",
                // the inbox and its search
                "search_bar_real_field", "search_results_container", "row_inbox_container",
                // a single reel, a single post
                "clips_viewer_view_pager", "root_clips_layout", "row_feed_photo_profile_name",
                // Home, which is locked in place, not bounced
                "main_feed_action_bar", "action_bar_title_view", "shimmer_content", "reels_tray_container",
                // containers every screen sits in
                "action_bar_root", "layout_container_main", "bottom_sheet", "fragment_container",
                "recycler_view", "list",
                // near misses
                "serp_journey_header_query_text", "clips_pivot_page", "location_detail", "",
        }) {
            assertNull("must not bounce on " + idName, PostboxSurface.forbiddenPage(idName));
        }
        assertNull(PostboxSurface.forbiddenPage(null));
    }

    @Test
    public void everyForbiddenViewHasANameAndAPage() {
        Set<String> names = new HashSet<>();
        for (String[] row : PostboxSurface.FORBIDDEN_VIEWS) {
            assertEquals(2, row.length);
            assertFalse(row[0].isEmpty());
            assertFalse(row[1].isEmpty());
            assertTrue("listed twice: " + row[0], names.add(row[0]));
        }
        assertEquals(4, names.size());
    }

    /** The title is only ever looked for inside the Home bar, and the tray is never a target. */
    @Test
    public void homeNamesAreTheOnesFromTheDump() {
        assertEquals("main_feed_action_bar", PostboxSurface.HOME_ACTION_BAR);
        assertEquals("action_bar_title_view", PostboxSurface.HOME_TITLE);
        assertEquals("shimmer_content", PostboxSurface.HOME_SHIMMER);
        assertEquals("reels_tray_container", PostboxSurface.STORIES_TRAY);
        assertNotEquals(PostboxSurface.HOME_SHIMMER, PostboxSurface.STORIES_TRAY);
        for (String chevron : PostboxSurface.HOME_TITLE_CHEVRONS) {
            assertNotEquals("the logo itself stays", "title_logo", chevron);
        }
    }

    @Test
    public void firstLookIsImmediate() {
        assertEquals(0L, PostboxSurface.passDelay(0, Postbox.NEVER));
        assertEquals(0L, PostboxSurface.passDelay(123_456L, Postbox.NEVER));
    }

    @Test
    public void aLayoutInsideTheGapWaitsForItsEnd() {
        long last = 1_000_000L;
        assertEquals(PostboxSurface.PASS_GAP_MS, PostboxSurface.passDelay(last, last));
        assertEquals(PostboxSurface.PASS_GAP_MS - 1, PostboxSurface.passDelay(last + 1, last));
        assertEquals(1L, PostboxSurface.passDelay(last + PostboxSurface.PASS_GAP_MS - 1, last));
    }

    @Test
    public void aLayoutAfterTheGapIsLookedAtNow() {
        long last = 1_000_000L;
        assertEquals(0L, PostboxSurface.passDelay(last + PostboxSurface.PASS_GAP_MS, last));
        assertEquals(0L, PostboxSurface.passDelay(last + 60_000, last));
        // A clock that reads earlier than the last look must not hold the next one off.
        assertEquals(0L, PostboxSurface.passDelay(last - 1, last));
    }
}
