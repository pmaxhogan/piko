package app.morphe.extension.instagram.patches.postbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import app.morphe.extension.instagram.patches.postbox.PostboxProfileNet.Kind;
import app.morphe.extension.instagram.patches.postbox.PostboxProfileNet.RehideBudget;
import app.morphe.extension.instagram.patches.postbox.PostboxProfileNet.Request;
import app.morphe.extension.instagram.patches.postbox.PostboxProfileNet.Verdict;

/** "Postbox profile grid" (D8) as pure functions: whose profile, which requests, the unknown rule. */
public class PostboxProfileNetTest {
    private static final String SELF = "25025320";
    private static final String SECOND_ACCOUNT = "1234567890123";
    private static final String OTHER = "987654321";
    private static final String MAIN_ACTIVITY = "com.instagram.mainactivity.InstagramMainActivity";

    private static final Set<String> NONE = Collections.emptySet();

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    @Before
    @After
    public void forgetEverything() {
        PostboxProfileNet.reset();
    }

    // ---- whose profile ----

    @Test
    public void sameIdIsTheUsersOwnProfile() {
        assertEquals(Verdict.SELF, PostboxProfileNet.classify(SELF, SELF, false));
        assertEquals(Verdict.SELF, PostboxProfileNet.classify(" " + SELF, SELF + " ", false));
    }

    @Test
    public void differentIdIsSomebodyElse() {
        assertEquals(Verdict.OTHER, PostboxProfileNet.classify(SELF, OTHER, false));
        // A prefix or a suffix of the own id is still somebody else.
        assertEquals(Verdict.OTHER, PostboxProfileNet.classify(SELF, SELF + "0", false));
        assertEquals(Verdict.OTHER, PostboxProfileNet.classify(SELF, SELF.substring(1), false));
    }

    /** No user behind the profile yet: not the own profile in the normal case, so it is hidden. */
    @Test
    public void profileWithoutAUserYetIsNotLoaded() {
        assertEquals(Verdict.NOT_LOADED, PostboxProfileNet.classify(SELF, null, false));
        assertEquals(Verdict.NOT_LOADED, PostboxProfileNet.classify(SELF, "", false));
        assertEquals(Verdict.NOT_LOADED, PostboxProfileNet.classify(SELF, "  ", false));
    }

    /** Failures that would repeat on every profile, the own one included, must not hide anything. */
    @Test
    public void whatCannotBeToldIsUnknown() {
        assertEquals(Verdict.UNKNOWN, PostboxProfileNet.classify(null, OTHER, false));
        assertEquals(Verdict.UNKNOWN, PostboxProfileNet.classify("", OTHER, false));
        assertEquals(Verdict.UNKNOWN, PostboxProfileNet.classify(" ", null, false));
        assertEquals(Verdict.UNKNOWN, PostboxProfileNet.classify(null, null, false));
        // Reading the profile's id threw: unknown even though both values look usable.
        assertEquals(Verdict.UNKNOWN, PostboxProfileNet.classify(SELF, OTHER, true));
        assertEquals(Verdict.UNKNOWN, PostboxProfileNet.classify(SELF, null, true));
        assertEquals(Verdict.UNKNOWN, PostboxProfileNet.classify(SELF, SELF, true));
    }

    @Test
    public void onlyOtherAndNotLoadedHideTheGrid() {
        assertTrue(PostboxProfileNet.hidesGrid(Verdict.OTHER));
        assertTrue(PostboxProfileNet.hidesGrid(Verdict.NOT_LOADED));
        assertFalse(PostboxProfileNet.hidesGrid(Verdict.SELF));
        assertFalse(PostboxProfileNet.hidesGrid(Verdict.UNKNOWN));
    }

    // ---- path parsing ----

    @Test
    public void userFeedIsTheExactShape() {
        for (String path : new String[] {
                "/api/v1/feed/user/987654321/",
                "/api/v1/feed/user/987654321",
                "/feed/user/987654321/",
        }) {
            Request request = Request.of(path);
            assertEquals(path, Kind.USER_FEED, request.kind);
            assertEquals(path, OTHER, request.target);
        }
    }

    @Test
    public void userFeedByNameCarriesTheLowerCaseName() {
        Request request = Request.of("/api/v1/feed/user/Some.Body_1/username/");
        assertEquals(Kind.USER_FEED_BY_NAME, request.kind);
        assertEquals("some.body_1", request.target);
        // A name made of digits is still a name in this form.
        assertEquals(Kind.USER_FEED_BY_NAME, Request.of("/api/v1/feed/user/123/username/").kind);
    }

    @Test
    public void taggedGridAndReelsGrid() {
        Request tags = Request.of("/api/v1/usertags/987654321/feed/");
        assertEquals(Kind.USER_TAGS, tags.kind);
        assertEquals(OTHER, tags.target);

        Request clips = Request.of("/api/v1/clips/user/");
        assertEquals(Kind.USER_CLIPS, clips.kind);
        assertNull(clips.target);
        assertEquals(Kind.USER_CLIPS, Request.of("/api/v1/clips/user").kind);
    }

    /** Everything that is not a grid, including the near misses the task names. */
    private static final String[] NOT_A_GRID = {
            // stories
            "/api/v1/feed/user/987654321/story/",
            "/api/v1/feed/user/25025320/story/",
            "/api/v1/feed/user/987654321/reel_media/",
            "/api/v1/feed/reels_tray/",
            "/api/v1/feed/reels_media/",
            // profile header, follower lists, highlights
            "/api/v1/users/987654321/info/",
            "/api/v1/users/987654321/info_stream/",
            "/api/v1/users/web_profile_info/",
            "/api/v1/users/some.body/usernameinfo/",
            "/api/v1/friendships/987654321/followers/",
            "/api/v1/friendships/987654321/following/",
            "/api/v1/friendships/show/987654321/",
            "/api/v1/highlights/987654321/highlights_tray/",
            // other shapes under the same prefixes
            "/api/v1/feed/user/",
            "/api/v1/feed/user",
            "/api/v1/feed/user//",
            "/api/v1/feed/user/987654321/username/more/",
            "/api/v1/feed/user/987654321//",
            "/api/v1/feed/user/some.body/",
            "/api/v1/feed/user/12a34/",
            "/api/v1/feed/user/-5/",
            "/api/v1/feed/user/987654321/x/",
            "/api/v1/feed/user//username/",
            "/api/v1/feed/users/987654321/",
            "/api/v1/feed/timeline/",
            "/api/v1/usertags/987654321/",
            "/api/v1/usertags/987654321/feed/more/",
            "/api/v1/usertags/review/",
            "/api/v1/usertags/abc/feed/",
            "/api/v1/clips/user/share_to_fb_config/",
            "/api/v1/clips/user/987654321/",
            "/api/v1/clips/users/",
            "/api/v1/clips/item/",
            "/api/v1/clips/home/",
            // direct, single items, accounts
            "/api/v1/direct_v2/inbox/",
            "/api/v1/media/3141592653589793238/info/",
            "/api/v1/accounts/current_user/",
            "/",
            "",
    };

    @Test
    public void nearMissesAreNotGridRequests() {
        for (String path : NOT_A_GRID) {
            assertSame("for " + path, Kind.NONE, Request.of(path).kind);
        }
        assertSame(Request.NONE, Request.of(null));
    }

    @Test
    public void idsAreDecimalNumbersOnly() {
        assertTrue(PostboxProfileNet.isId("1"));
        assertTrue(PostboxProfileNet.isId(SECOND_ACCOUNT));
        for (String value : new String[] {null, "", " ", "1 ", "-1", "1.0", "0x10", "abc", "12a", "1/2"}) {
            assertFalse("for [" + value + "]", PostboxProfileNet.isId(value));
        }
        // Longer than any account id.
        assertFalse(PostboxProfileNet.isId("1234567890123456789012345"));
    }

    // ---- the decision ----

    @Test
    public void someoneElsesGridIsBlockedOnceTheOwnIdIsKnown() {
        Set<String> self = set(SELF);
        assertTrue(PostboxProfileNet.isBlocked(Request.of("/api/v1/feed/user/" + OTHER + "/"), self, NONE, false));
        assertTrue(PostboxProfileNet.isBlocked(Request.of("/api/v1/usertags/" + OTHER + "/feed/"), self, NONE, false));
    }

    @Test
    public void theOwnGridIsNeverBlocked() {
        Set<String> self = set(SELF);
        assertFalse(PostboxProfileNet.isBlocked(Request.of("/api/v1/feed/user/" + SELF + "/"), self, NONE, false));
        assertFalse(PostboxProfileNet.isBlocked(Request.of("/api/v1/usertags/" + SELF + "/feed/"), self, NONE, false));
        // Not even while somebody else's profile is the one in front.
        assertFalse(PostboxProfileNet.isBlocked(Request.of("/api/v1/feed/user/" + SELF + "/"), self, NONE, true));
    }

    /** The unknown rule: with no logged-in id known, nothing that depends on one is blocked. */
    @Test
    public void nothingIdBasedIsBlockedWhileTheOwnIdIsUnknown() {
        assertFalse(PostboxProfileNet.isBlocked(Request.of("/api/v1/feed/user/" + OTHER + "/"), NONE, NONE, false));
        assertFalse(PostboxProfileNet.isBlocked(Request.of("/api/v1/feed/user/" + SELF + "/"), NONE, NONE, false));
        assertFalse(PostboxProfileNet.isBlocked(Request.of("/api/v1/usertags/" + OTHER + "/feed/"), NONE, NONE, false));
        assertFalse(PostboxProfileNet.isBlocked(Request.of("/api/v1/feed/user/some.body/username/"), NONE, NONE, false));
        // A name is known but no id: still unknown.
        assertFalse(PostboxProfileNet.isBlocked(
                Request.of("/api/v1/feed/user/some.body/username/"), NONE, set("me"), false));
    }

    /** Several logged-in accounts: each one's own grid loads. */
    @Test
    public void everyLoggedInAccountIsSelf() {
        Set<String> self = set(SELF, SECOND_ACCOUNT);
        assertFalse(PostboxProfileNet.isBlocked(Request.of("/api/v1/feed/user/" + SELF + "/"), self, NONE, false));
        assertFalse(PostboxProfileNet.isBlocked(
                Request.of("/api/v1/feed/user/" + SECOND_ACCOUNT + "/"), self, NONE, false));
        assertTrue(PostboxProfileNet.isBlocked(Request.of("/api/v1/feed/user/" + OTHER + "/"), self, NONE, false));
    }

    @Test
    public void byNameNeedsAKnownOwnName() {
        Request other = Request.of("/api/v1/feed/user/some.body/username/");
        Request own = Request.of("/api/v1/feed/user/Me.Myself/username/");
        // The id is known but no own username yet: it could be the user's own, so it passes.
        assertFalse(PostboxProfileNet.isBlocked(other, set(SELF), NONE, false));
        assertFalse(PostboxProfileNet.isBlocked(own, set(SELF), NONE, false));
        // With the own name known, only the own one passes.
        assertTrue(PostboxProfileNet.isBlocked(other, set(SELF), set("me.myself"), false));
        assertFalse(PostboxProfileNet.isBlocked(own, set(SELF), set("me.myself"), false));
    }

    /** The reels grid names nobody in its path: it goes by which profile is in front. */
    @Test
    public void reelsGridGoesByTheProfileInFront() {
        Request clips = Request.of("/api/v1/clips/user/");
        assertTrue(PostboxProfileNet.isBlocked(clips, set(SELF), NONE, true));
        assertFalse(PostboxProfileNet.isBlocked(clips, set(SELF), NONE, false));
        // It does not depend on a known id either way.
        assertTrue(PostboxProfileNet.isBlocked(clips, NONE, NONE, true));
        assertFalse(PostboxProfileNet.isBlocked(clips, NONE, NONE, false));
    }

    @Test
    public void whatIsNotAGridIsNeverBlocked() {
        Set<String> self = set(SELF);
        for (String path : NOT_A_GRID) {
            assertFalse("must not block " + path, PostboxProfileNet.isBlocked(Request.of(path), self, set("me"), true));
        }
        assertFalse(PostboxProfileNet.isBlocked(null, self, NONE, true));
        assertFalse(PostboxProfileNet.isBlocked(Request.NONE, self, NONE, true));
    }

    // ---- the holder ----

    @Test
    public void nothingIsBlockedBeforeAnyBind() {
        assertEquals(0, PostboxProfileNet.knownSelfIds());
        assertFalse(PostboxProfileNet.isBlockedNow("/api/v1/feed/user/" + OTHER + "/"));
        assertFalse(PostboxProfileNet.isBlockedNow("/api/v1/feed/user/" + SELF + "/"));
        assertFalse(PostboxProfileNet.isBlockedNow("/api/v1/clips/user/"));
        assertFalse(PostboxProfileNet.isBlockedNow(null));
    }

    @Test
    public void aBindTeachesTheOwnId() {
        PostboxProfileNet.learnSelfId(SELF, MAIN_ACTIVITY);
        assertTrue(PostboxProfileNet.isBlockedNow("/api/v1/feed/user/" + OTHER + "/"));
        assertFalse(PostboxProfileNet.isBlockedNow("/api/v1/feed/user/" + SELF + "/"));
        assertFalse(PostboxProfileNet.isBlockedNow("/api/v1/feed/user/" + OTHER + "/story/"));
        assertFalse(PostboxProfileNet.isBlockedNow("/api/v1/users/" + OTHER + "/info/"));
        assertFalse(PostboxProfileNet.isBlockedNow("/api/v1/feed/reels_tray/"));
        assertFalse(PostboxProfileNet.isBlockedNow("/api/v1/friendships/" + OTHER + "/followers/"));
        assertFalse(PostboxProfileNet.isBlockedNow("/api/v1/friendships/" + OTHER + "/following/"));
        assertFalse(PostboxProfileNet.isBlockedNow("/api/v1/highlights/" + OTHER + "/highlights_tray/"));
    }

    @Test
    public void blankIdsTeachNothing() {
        PostboxProfileNet.learnSelfId(null, MAIN_ACTIVITY);
        PostboxProfileNet.learnSelfId("", MAIN_ACTIVITY);
        PostboxProfileNet.learnSelfId("  ", MAIN_ACTIVITY);
        assertEquals(0, PostboxProfileNet.knownSelfIds());
        assertFalse(PostboxProfileNet.isBlockedNow("/api/v1/feed/user/" + OTHER + "/"));
    }

    /** Account switch: the activity the profiles live in starts again, and the ids are dropped. */
    @Test
    public void aRestartOfTheHostActivityForgetsTheIds() {
        PostboxProfileNet.learnSelfId(SELF, MAIN_ACTIVITY);
        PostboxProfileNet.learnSelfName("Me.Myself");
        assertTrue(PostboxProfileNet.isBlockedNow("/api/v1/feed/user/" + SECOND_ACCOUNT + "/"));

        // Some other activity (settings, a camera) changes nothing.
        assertFalse(PostboxProfileNet.forgetSelfIfHost("app.morphe.extension.instagram.settings.SettingsActivity"));
        assertFalse(PostboxProfileNet.forgetSelfIfHost(null));
        assertTrue(PostboxProfileNet.isBlockedNow("/api/v1/feed/user/" + SECOND_ACCOUNT + "/"));

        assertTrue(PostboxProfileNet.forgetSelfIfHost(MAIN_ACTIVITY));
        assertEquals(0, PostboxProfileNet.knownSelfIds());
        // Now logged in as the second account, before any profile was bound: its own grid loads.
        assertFalse(PostboxProfileNet.isBlockedNow("/api/v1/feed/user/" + SECOND_ACCOUNT + "/"));
        assertFalse(PostboxProfileNet.isBlockedNow("/api/v1/feed/user/some.body/username/"));
        // Nothing left to forget the second time.
        assertFalse(PostboxProfileNet.forgetSelfIfHost(MAIN_ACTIVITY));

        PostboxProfileNet.learnSelfId(SECOND_ACCOUNT, MAIN_ACTIVITY);
        assertFalse(PostboxProfileNet.isBlockedNow("/api/v1/feed/user/" + SECOND_ACCOUNT + "/"));
        // The first account is somebody else now, exactly as the view layer sees it.
        assertTrue(PostboxProfileNet.isBlockedNow("/api/v1/feed/user/" + SELF + "/"));
    }

    /** Profiles bound in more than one activity class: a restart of any of them forgets. */
    @Test
    public void everyActivityThatHostedABindCounts() {
        String modal = "com.instagram.modal.ModalActivity";
        PostboxProfileNet.learnSelfId(SELF, MAIN_ACTIVITY);
        PostboxProfileNet.learnSelfId(SELF, modal);
        PostboxProfileNet.learnSelfId(SELF, null);
        assertEquals(1, PostboxProfileNet.knownSelfIds());
        assertTrue(PostboxProfileNet.forgetSelfIfHost(MAIN_ACTIVITY));
        assertEquals(0, PostboxProfileNet.knownSelfIds());
        PostboxProfileNet.learnSelfId(SELF, null);
        assertTrue(PostboxProfileNet.forgetSelfIfHost(modal));
        assertEquals(0, PostboxProfileNet.knownSelfIds());
    }

    @Test
    public void theFrontFlagOnlyDrivesTheReelsGrid() {
        PostboxProfileNet.learnSelfId(SELF, MAIN_ACTIVITY);
        assertFalse(PostboxProfileNet.isBlockedNow("/api/v1/clips/user/"));
        PostboxProfileNet.setOtherInFront(true);
        assertTrue(PostboxProfileNet.isOtherInFront());
        assertTrue(PostboxProfileNet.isBlockedNow("/api/v1/clips/user/"));
        assertFalse(PostboxProfileNet.isBlockedNow("/api/v1/feed/user/" + SELF + "/"));
        assertFalse(PostboxProfileNet.isBlockedNow("/api/v1/clips/item/"));
        PostboxProfileNet.setOtherInFront(false);
        assertFalse(PostboxProfileNet.isBlockedNow("/api/v1/clips/user/"));
    }

    @Test
    public void matchesThePathOfARealRequestUri() throws Exception {
        PostboxProfileNet.learnSelfId(SELF, MAIN_ACTIVITY);
        URI grid = new URI("https://i.instagram.com/api/v1/feed/user/" + OTHER + "/?count=12&max_id=1_2");
        assertTrue(PostboxProfileNet.isBlockedNow(grid.getPath()));
        // The own id in the query of somebody else's request does not make it the own grid.
        URI tricky = new URI("https://i.instagram.com/api/v1/feed/user/" + OTHER + "/?viewer=" + SELF);
        assertTrue(PostboxProfileNet.isBlockedNow(tricky.getPath()));
        URI story = new URI("https://i.instagram.com/api/v1/feed/user/" + OTHER + "/story/?supported_capabilities_new=x");
        assertFalse(PostboxProfileNet.isBlockedNow(story.getPath()));
    }

    /** With the build switch off (the source default) the network hook ignores all of it. */
    @Test
    public void interceptUriDoesNothingWhileTheFeatureIsOff() throws Exception {
        PostboxProfileNet.learnSelfId(SELF, MAIN_ACTIVITY);
        PostboxProfileNet.setOtherInFront(true);
        PostboxNet.interceptUri(new URI("https://i.instagram.com/api/v1/feed/user/" + OTHER + "/"));
        PostboxNet.interceptUri(new URI("https://i.instagram.com/api/v1/clips/user/"));
    }

    // ---- the re-hide budget ----

    @Test
    public void rehideBudgetAllowsABurstPerWindow() {
        RehideBudget budget = new RehideBudget();
        long start = 10_000L;
        for (int index = 0; index < RehideBudget.BURST; index++) {
            assertTrue("take " + index, budget.take(start + index));
        }
        assertFalse(budget.take(start + RehideBudget.BURST));
        assertFalse(budget.take(start + RehideBudget.WINDOW_MS - 1));
    }

    @Test
    public void rehideBudgetRefillsWhenTheWindowEnds() {
        RehideBudget budget = new RehideBudget();
        long start = 10_000L;
        for (int index = 0; index < RehideBudget.BURST; index++) budget.take(start);
        assertFalse(budget.take(start + 1));
        assertTrue(budget.take(start + RehideBudget.WINDOW_MS));
        // The window restarted at the refill, not at the first take.
        for (int index = 1; index < RehideBudget.BURST; index++) {
            assertTrue(budget.take(start + RehideBudget.WINDOW_MS + index));
        }
        assertFalse(budget.take(start + RehideBudget.WINDOW_MS + RehideBudget.BURST));
    }

    @Test
    public void rehideBudgetResetStartsOver() {
        RehideBudget budget = new RehideBudget();
        for (int index = 0; index < RehideBudget.BURST; index++) budget.take(5);
        assertFalse(budget.take(6));
        budget.reset();
        assertTrue(budget.take(6));
    }
}
