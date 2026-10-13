package app.morphe.extension.instagram.patches.postbox;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.net.URI;

import org.junit.Test;

import app.morphe.extension.instagram.patches.postbox.PostboxLinks.Verdict;

/** The deep-link table (L2, D12): what opens as a single item and what lands on the inbox. */
public class PostboxLinksTest {
    private static void allow(String... links) {
        for (String link : links) assertEquals("for " + link, Verdict.ALLOW, PostboxLinks.classify(link));
    }

    private static void bounce(String... links) {
        for (String link : links) assertEquals("for " + link, Verdict.BOUNCE, PostboxLinks.classify(link));
    }

    // ---- the acceptance checklist, by name (spec section 7) ---------------------------------

    /** Item 14: every one of these must land on the inbox. */
    @Test
    public void specItem14ForbiddenRoutesBounce() {
        bounce(
                "https://www.instagram.com/explore/",
                "https://www.instagram.com/reels/",
                "https://www.instagram.com/",
                "https://www.instagram.com/explore/tags/cats/",
                "instagram://explore",
                "instagram://reels",
                "instagram://feed");
    }

    /** Item 8: a post link and a reel link from another app open as single items. */
    @Test
    public void specItem8PostAndReelLinksOpen() {
        allow(
                "https://www.instagram.com/p/DAbCdEfGhIj/",
                "https://instagram.com/p/DAbCdEfGhIj/",
                "https://www.instagram.com/reel/DAbCdEfGhIj/",
                "https://instagram.com/reel/DAbCdEfGhIj/",
                // as share sheets actually produce them
                "https://www.instagram.com/p/DAbCdEfGhIj/?igsh=MXZ4dGZtY2ZmZ2Zo",
                "https://www.instagram.com/reel/DAbCdEfGhIj/?igsh=MXZ4dGZtY2ZmZ2Zo&utm_source=qr",
                "https://www.instagram.com/p/DAbCdEfGhIj/?img_index=2");
    }

    // ---- single reels open through the post viewer (L2) -------------------------------------

    private static final String AS_POST = "https://www.instagram.com/p/DAbCdEfGhIj/";

    private static void opensAsPost(String... links) {
        for (String link : links) assertEquals("for " + link, AS_POST, PostboxLinks.reelAsPostLink(link));
    }

    private static void notRewritten(String... links) {
        for (String link : links) assertNull("for " + link, PostboxLinks.reelAsPostLink(link));
    }

    /** /reel/<code> and /reels/<code>, with and without the trailing slash, on every app-link host. */
    @Test
    public void singleReelLinksBecomePostLinks() {
        opensAsPost(
                "https://www.instagram.com/reel/DAbCdEfGhIj/",
                "https://www.instagram.com/reel/DAbCdEfGhIj",
                "https://www.instagram.com/reels/DAbCdEfGhIj/",
                "https://www.instagram.com/reels/DAbCdEfGhIj",
                "https://instagram.com/reel/DAbCdEfGhIj/",
                "http://www.instagram.com/reel/DAbCdEfGhIj/",
                "https://m.instagram.com/reel/DAbCdEfGhIj/",
                "https://instagr.am/reel/DAbCdEfGhIj/",
                "HTTPS://WWW.INSTAGRAM.COM/Reel/DAbCdEfGhIj/",
                // with a username in front, as profile pages link them
                "https://www.instagram.com/some.user_1/reel/DAbCdEfGhIj/");
    }

    /** Only the shortcode is carried over: no share token, no fragment, nothing after the code. */
    @Test
    public void queryFragmentAndTrailingSegmentsAreDropped() {
        opensAsPost(
                "https://www.instagram.com/reel/DAbCdEfGhIj/?igsh=MXZ4dGZtY2ZmZ2Zo",
                "https://www.instagram.com/reel/DAbCdEfGhIj/?igsh=MXZ4dGZtY2ZmZ2Zo&utm_source=qr",
                "https://www.instagram.com/reels/DAbCdEfGhIj/?next=/explore/",
                "https://www.instagram.com/reel/DAbCdEfGhIj?igsh=x#frag",
                "https://www.instagram.com/reel/DAbCdEfGhIj/embed/",
                "https://www.instagram.com/some.user_1/reel/DAbCdEfGhIj/?igsh=x");
        assertEquals("https://www.instagram.com/p/B-_x9aQhK3z/",
                PostboxLinks.reelAsPostLink("https://www.instagram.com/reel/B-_x9aQhK3z/?igsh=SECRET"));
        assertEquals(AS_POST, PostboxLinks.reelAsPostLink("https", "www.instagram.com", "/reel/DAbCdEfGhIj/"));
    }

    /** The Reels tab and its listings are not a reel: they are left to the table, which bounces them. */
    @Test
    public void bareReelsAndListingsAreNotRewritten() {
        String[] listings = {
                "https://www.instagram.com/reels/",
                "https://www.instagram.com/reels",
                "https://www.instagram.com/reel/",
                "https://www.instagram.com/reels/audio/1234567890/",
                "https://www.instagram.com/reels/trending/",
                // no upper-case letter or digit: read as a page name, like the table does
                "https://www.instagram.com/reels/somepage/",
                "https://www.instagram.com/explore/reel/DAbCdEfGhIj/",
                "https://www.instagram.com/some.user_1/reels/",
                "https://www.instagram.com/some.user_1/reel/",
        };
        notRewritten(listings);
        bounce(listings);
    }

    /** A post link passes straight through, which is also why a rewrite cannot be rewritten again. */
    @Test
    public void postLinksAndOtherItemsAreNotRewritten() {
        notRewritten(
                "https://www.instagram.com/p/DAbCdEfGhIj/",
                "https://www.instagram.com/p/DAbCdEfGhIj/?igsh=x",
                "https://www.instagram.com/some.user_1/p/DAbCdEfGhIj/",
                // the post whose shortcode happens to be "reel"
                "https://www.instagram.com/p/reel/DAbCdEfGhIj/",
                "https://www.instagram.com/tv/DAbCdEfGhIj/",
                "https://www.instagram.com/some.user_1/",
                "https://www.instagram.com/stories/some.user_1/3141592653589793238/",
                "https://www.instagram.com/direct/t/340282366841710300949128137443944319108/",
                // a share id is not a shortcode
                "https://www.instagram.com/share/reel/BAbCdEfGhI/",
                "https://www.instagram.com/share/BAbCdEfGhI/",
                // first segments with a meaning of their own are never a username
                "https://www.instagram.com/s/reel/DAbCdEfGhIj/",
                "https://www.instagram.com/stories/reel/DAbCdEfGhIj/",
                "https://www.instagram.com/direct/reel/DAbCdEfGhIj/",
                "https://www.instagram.com/accounts/reel/DAbCdEfGhIj/",
                "https://www.instagram.com/login/reel/DAbCdEfGhIj/",
                "https://www.instagram.com/about/reel/DAbCdEfGhIj/",
                "https://www.instagram.com/_u/reel/DAbCdEfGhIj/",
                "https://www.instagram.com/_n/reel/DAbCdEfGhIj/");
    }

    /** Only http(s) links on the app-link hosts. The app's own scheme carries media ids, not shortcodes. */
    @Test
    public void otherSchemesAndHostsAreNotRewritten() {
        notRewritten(
                "instagram://reel?id=3141592653589793238",
                "instagram://reels_share?shortcode=DAbCdEfGhIj",
                "instagram://media?id=3141592653589793238",
                "ig://reel/DAbCdEfGhIj",
                "https://help.instagram.com/reel/DAbCdEfGhIj/",
                "https://l.instagram.com/reel/DAbCdEfGhIj/",
                "https://ig.me/reel/DAbCdEfGhIj/",
                "https://example.com/reel/DAbCdEfGhIj/",
                "https:///reel/DAbCdEfGhIj/",
                "content://media/reel/DAbCdEfGhIj",
                "/reel/DAbCdEfGhIj/",
                "");
        assertNull(PostboxLinks.reelAsPostLink(null));
        assertNull(PostboxLinks.reelAsPostLink(null, null, null));
        assertNull(PostboxLinks.reelAsPostLink("https", "www.instagram.com", null));
    }

    /** Path tricks do not turn something else into a post link. */
    @Test
    public void malformedReelPathsAreNotRewritten() {
        notRewritten(
                "https://www.instagram.com/reel/../explore/",
                "https://www.instagram.com/reel/DAb%20CdE/",
                "https://www.instagram.com/reel/DAb.CdE/",
                "https://www.instagram.com/reel/%2e%2e/",
                "https://www.instagram.com/not a user/reel/DAbCdEfGhIj/");
        // An encoded slash is a slash once decoded (as android.net.Uri reports the path), so
        // this is the reel "DAb". Whatever comes out is built from a checked shortcode alone.
        assertEquals("https://www.instagram.com/p/DAb/",
                PostboxLinks.reelAsPostLink("https://www.instagram.com/reel/DAb%2FCdE/x"));
    }

    /**
     * What a rewrite produces opens (it is a post link) and is not a reel link, so it is never
     * rewritten a second time, whatever it started as.
     */
    @Test
    public void aRewrittenLinkIsAllowedAndIsNeverRewrittenAgain() {
        for (String link : new String[] {
                "https://www.instagram.com/reel/DAbCdEfGhIj/",
                "https://www.instagram.com/reels/DAbCdEfGhIj/?igsh=x",
                "https://instagr.am/some.user_1/reel/DAbCdEfGhIj",
                // shortcodes that look like route names
                "https://www.instagram.com/reel/reel/",
                "https://www.instagram.com/reel/reels/",
                "https://www.instagram.com/reel/p/",
        }) {
            String post = PostboxLinks.reelAsPostLink(link);
            assertEquals("for " + link, true, post != null && post.startsWith("https://www.instagram.com/p/"));
            assertEquals("for " + post, Verdict.ALLOW, PostboxLinks.classify(post));
            assertNull("for " + post, PostboxLinks.reelAsPostLink(post));
        }
    }

    // ---- web: single items ------------------------------------------------------------------

    @Test
    public void postsOpen() {
        allow(
                "https://www.instagram.com/p/C/",
                "https://www.instagram.com/p/B-_x9aQhK3z",
                "http://www.instagram.com/p/DAbCdEfGhIj/",
                "https://m.instagram.com/p/DAbCdEfGhIj/",
                "https://instagr.am/p/DAbCdEfGhIj/",
                "https://www.instagr.am/p/DAbCdEfGhIj/",
                "https://www.instagram.com/p/DAbCdEfGhIj/c/17900000000000000/",
                "https://www.instagram.com/p/DAbCdEfGhIj/liked_by/",
                "https://www.instagram.com/some.user_1/p/DAbCdEfGhIj/",
                "https://www.instagram.com/tv/DAbCdEfGhIj/");
    }

    @Test
    public void reelsOpenAsSingleItems() {
        allow(
                "https://www.instagram.com/reel/DAbCdEfGhIj",
                "https://www.instagram.com/reels/DAbCdEfGhIj/",
                "https://www.instagram.com/reels/DAbCdEfGhIj",
                "https://www.instagram.com/some.user_1/reel/DAbCdEfGhIj/",
                "https://www.instagram.com/share/reel/BAbCdEfGhI",
                "https://www.instagram.com/share/p/BAbCdEfGhI",
                "https://www.instagram.com/share/r/BAbCdEfGhI/",
                "https://www.instagram.com/share/BAbCdEfGhI");
    }

    @Test
    public void storiesAndHighlightsOpen() {
        allow(
                "https://www.instagram.com/stories/some.user_1/3141592653589793238/",
                "https://www.instagram.com/stories/some.user_1/3141592653589793238?igsh=abc",
                "https://www.instagram.com/stories/some.user_1/",
                "https://www.instagram.com/stories/highlights/17900000000000000/",
                "https://www.instagram.com/s/aGlnaGxpZ2h0OjE3OTAwMDAw?story_media_id=1_2");
    }

    @Test
    public void directLinksOpen() {
        allow(
                "https://www.instagram.com/direct/inbox/",
                "https://www.instagram.com/direct/",
                "https://www.instagram.com/direct/t/340282366841710300949128137443944319108/",
                "https://www.instagram.com/direct/new/",
                "https://www.instagram.com/direct/requests/",
                "https://ig.me/m/some.user_1",
                "https://ig.me/j/AbCdEfGhIjKlMnOp/");
    }

    @Test
    public void profilesOpen() {
        allow(
                "https://www.instagram.com/some.user_1",
                "https://www.instagram.com/some.user_1/",
                "https://www.instagram.com/some.user_1/?igsh=MXZ4dGZtY2Zm&utm_source=qr",
                "https://www.instagram.com/some.user_1?hl=en",
                "https://instagram.com/SomeUser",
                "https://www.instagram.com/_u/some.user_1",
                "https://www.instagram.com/_u/some.user_1/?utm_source=ig_web_button_share_sheet",
                "https://www.instagram.com/some.user_1/followers/",
                "https://www.instagram.com/some.user_1/following/",
                // thirty characters, the longest a username can be
                "https://www.instagram.com/abcdefghijklmnopqrstuvwxyz1234/");
    }

    @Test
    public void accountAndLoginPagesOpen() {
        allow(
                "https://www.instagram.com/accounts/login/",
                "https://www.instagram.com/accounts/login/?next=%2Fdirect%2Finbox%2F",
                "https://www.instagram.com/accounts/login/two_factor/",
                "https://www.instagram.com/accounts/password/reset/",
                "https://www.instagram.com/accounts/password/reset/confirm/?uidb36=abc&token=def",
                "https://www.instagram.com/accounts/confirm_email/AbCdEf/dXNlckBleGFtcGxlLmNvbQ/",
                "https://www.instagram.com/accounts/emailsignup/",
                "https://www.instagram.com/accounts/onetap/",
                "https://www.instagram.com/accounts/edit/",
                "https://www.instagram.com/accounts/logout/",
                "https://www.instagram.com/accounts/",
                "https://www.instagram.com/challenge/",
                "https://www.instagram.com/challenge/action/AbCdEf/GhIjKl/",
                "https://www.instagram.com/oauth/authorize/?client_id=1",
                "https://www.instagram.com/emails/unsubscribe/",
                "https://www.instagram.com/session/login_activity/",
                "https://www.instagram.com/auth_platform/codeentry/",
                "https://www.instagram.com/legal/privacy/",
                "https://www.instagram.com/about/us/",
                // the one-tap login and confirmation links sent by email
                "https://www.instagram.com/_n/emaillogin?uid=abc&token=def",
                "https://instagram.com/_n/web_emaillogin?uid=abc&token=def",
                "https://www.instagram.com/_n/confirm_email?token=def");
    }

    // ---- web: forbidden surfaces ------------------------------------------------------------

    @Test
    public void homeBounces() {
        bounce(
                "https://www.instagram.com",
                "https://www.instagram.com/",
                "https://instagram.com/",
                "http://instagram.com",
                "https://m.instagram.com/",
                "https://instagr.am/",
                "https://www.instagram.com/?hl=en",
                "https://www.instagram.com/?next=/explore/",
                "https://www.instagram.com/#reels",
                "https://www.instagram.com//",
                "https://www.instagram.com/_n/mainfeed");
    }

    @Test
    public void exploreAndEverythingUnderItBounces() {
        bounce(
                "https://www.instagram.com/explore",
                "https://www.instagram.com/explore/",
                "https://www.instagram.com/explore/tags/cats/",
                "https://www.instagram.com/explore/locations/212988663/new-york-new-york/",
                "https://www.instagram.com/explore/search/",
                "https://www.instagram.com/explore/search/keyword/?q=cats",
                "https://www.instagram.com/explore/people/",
                "https://www.instagram.com/explore/p/DAbCdEfGhIj/",
                "https://instagr.am/explore/",
                "https://www.instagram.com/_n/explore");
    }

    @Test
    public void reelsTabAndListingsBounce() {
        bounce(
                "https://www.instagram.com/reels",
                "https://www.instagram.com/reels/",
                "https://www.instagram.com/reels/?hl=en",
                "https://www.instagram.com/reels/audio/1234567890/",
                "https://www.instagram.com/reels/audio/",
                "https://www.instagram.com/reels/trending/",
                "https://www.instagram.com/reels/explore/",
                "https://www.instagram.com/reels/videos/",
                // not on any list, but a lower-case word is a page of the tab, not a shortcode
                "https://www.instagram.com/reels/followers/",
                "https://www.instagram.com/reels/some_new_page/",
                "https://www.instagram.com/reels/AUDIO/",
                "https://www.instagram.com/reel/",
                "https://www.instagram.com/clips/",
                "https://www.instagram.com/clips/home/",
                "https://www.instagram.com/clips/trends/",
                "https://www.instagram.com/some.user_1/reels/",
                "https://www.instagram.com/_n/reels_home",
                "https://www.instagram.com/_n/clips_home");
    }

    @Test
    public void hashtagLocationSearchAndTopicPagesBounce() {
        bounce(
                "https://www.instagram.com/tags/cats/",
                "https://www.instagram.com/locations/212988663/",
                "https://www.instagram.com/search/",
                "https://www.instagram.com/web/search/topsearch/?query=cats",
                "https://www.instagram.com/web/search/",
                "https://www.instagram.com/topics/",
                "https://www.instagram.com/topics/food-and-drink/",
                "https://www.instagram.com/discover/",
                "https://www.instagram.com/discover/people/",
                "https://www.instagram.com/tv/",
                "https://www.instagram.com/igtv/",
                "https://www.instagram.com/some.user_1/channel/",
                "https://www.instagram.com/stories/tags/cats/",
                "https://www.instagram.com/stories/locations/212988663/",
                "https://www.instagram.com/stories/",
                "https://www.instagram.com/accounts/suggested/",
                "https://www.instagram.com/audio/1234567890/");
    }

    /** Grids on other profiles are not part of Postbox, and unknown deeper paths fail closed. */
    @Test
    public void unknownMultiSegmentPathsBounce() {
        bounce(
                "https://www.instagram.com/some.user_1/tagged/",
                "https://www.instagram.com/some.user_1/saved/",
                "https://www.instagram.com/some.user_1/guides/",
                "https://www.instagram.com/some.user_1/live/",
                "https://www.instagram.com/some.user_1/whatever/",
                "https://www.instagram.com/some.user_1/p/",
                "https://www.instagram.com/some.user_1/reel/",
                "https://www.instagram.com/some.user_1/x/DAbCdEfGhIj/",
                "https://www.instagram.com/_u/",
                "https://www.instagram.com/_u/some.user_1/tagged/",
                "https://www.instagram.com/_n/",
                "https://www.instagram.com/share/",
                "https://www.instagram.com/s/",
                "https://ig.me/",
                "https://ig.me/x/whatever");
    }

    /** Reserved first segments are Instagram routes, never somebody's username. */
    @Test
    public void reservedWordsAreNotUsernames() {
        for (String word : new String[] {
                "explore", "reels", "reel", "p", "tv", "stories", "s", "web", "api", "graphql", "static",
                "developer", "tags", "locations", "search", "topics", "discover", "clips", "igtv",
                "share", "_u", "_n", "feed", "home", "audio", "ajax", "embed", "invites", "threads",
                "saved", "archive", "notifications", "create", "press", "business", "direct_v2",
        }) {
            bounce("https://www.instagram.com/" + word, "https://www.instagram.com/" + word + "/");
        }
        // The reserved words that are let through are let through as pages, not as profiles:
        // the same word with a profile-only suffix is still not treated as a user.
        allow("https://www.instagram.com/direct/", "https://www.instagram.com/accounts/",
                "https://www.instagram.com/about/", "https://www.instagram.com/legal/",
                "https://www.instagram.com/privacy/", "https://www.instagram.com/emails/",
                "https://www.instagram.com/challenge/", "https://www.instagram.com/oauth/",
                "https://www.instagram.com/session/");
        bounce("https://www.instagram.com/explore/followers/", "https://www.instagram.com/reels/followers/",
                "https://www.instagram.com/web/followers/", "https://www.instagram.com/api/followers/");
    }

    @Test
    public void whatCannotBeAUsernameBounces() {
        bounce(
                "https://www.instagram.com/some-user/",
                "https://www.instagram.com/some%20user/",
                "https://www.instagram.com/@someuser/",
                "https://www.instagram.com/some+user/",
                // thirty-one characters
                "https://www.instagram.com/abcdefghijklmnopqrstuvwxyz12345/",
                "https://www.instagram.com/%252e%252e/");
    }

    // ---- web: evasions ----------------------------------------------------------------------

    @Test
    public void caseAndEncodingDoNotGetAroundTheTable() {
        bounce(
                "HTTPS://WWW.INSTAGRAM.COM/EXPLORE/",
                "https://WWW.Instagram.Com/Explore/Tags/cats/",
                "https://www.instagram.com/Reels/",
                "https://www.instagram.com/%65xplore/",
                "https://www.instagram.com/%45XPLORE/",
                "https://www.instagram.com/explore%2Ftags%2Fcats",
                "https://www.instagram.com//explore//",
                "https://www.instagram.com/./explore/",
                "https://www.instagram.com/p/../explore/",
                "https://www.instagram.com/p/%2e%2e/explore/",
                "https://www.instagram.com/p/DAbCdEfGhIj/../../explore/",
                "https://www.instagram.com/direct/../explore/",
                "https://www.instagram.com/accounts/../reels/",
                "https://www.instagram.com/explore\\tags",
                "https://www.instagram.com/some.user_1\\..\\explore",
                "https://www.instagram.com./explore/",
                "https://www.instagram.com:443/explore/",
                "https://user:pass@www.instagram.com/explore/",
                "https://evil.example@www.instagram.com/reels/",
                " https://www.instagram.com/explore/ ".trim());
        allow(
                "HTTPS://WWW.INSTAGRAM.COM/p/DAbCdEfGhIj/",
                "https://www.instagram.com:443/p/DAbCdEfGhIj/",
                "https://www.instagram.com./reel/DAbCdEfGhIj/",
                "https://www.instagram.com/P/DAbCdEfGhIj/",
                "https://www.instagram.com/%70/DAbCdEfGhIj/");
    }

    @Test
    public void queryAndFragmentNeverDecideAWebLink() {
        allow("https://www.instagram.com/p/DAbCdEfGhIj/?next=/explore/#/explore/");
        bounce("https://www.instagram.com/explore/?next=/p/DAbCdEfGhIj/#/p/DAbCdEfGhIj/");
        // A '?' or '#' ends the path wherever it is.
        bounce("https://www.instagram.com?x=/p/DAbCdEfGhIj/", "https://www.instagram.com#/p/DAbCdEfGhIj/");
    }

    @Test
    public void httpWithoutAHostBounces() {
        bounce("https:///explore/", "https:/explore/", "https://", "http:", "https:instagram.com/explore");
    }

    // ---- web: other hosts -------------------------------------------------------------------

    /** Not Instagram navigation: media shared in, the in-app browser, other apps' schemes. */
    @Test
    public void otherHostsAndSchemesAreNotPoliced() {
        allow(
                "https://example.com/",
                "https://example.com/explore/",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://notinstagram.com/explore/",
                "https://instagram.com.evil.example/explore/",
                "https://www.instagram.com@evil.example/explore/",
                "https://www.facebook.com/",
                "content://media/external/images/media/42",
                "file:///sdcard/DCIM/photo.jpg",
                "instagram-stories://share",
                "instagram-reels://share",
                "mailto:someone@example.com",
                "tel:+15555550100",
                "fb://profile/1",
                "market://details?id=com.instagram.android");
    }

    /** Other Instagram web properties: only the paths that name a forbidden surface bounce. */
    @Test
    public void otherInstagramSubdomainsAreLenient() {
        allow(
                "https://help.instagram.com/",
                "https://help.instagram.com/1234567890",
                "https://about.instagram.com/blog/",
                "https://accountscenter.instagram.com/password_and_security/",
                "https://privacycenter.instagram.com/policy",
                "https://l.instagram.com/?u=https%3A%2F%2Fexample.com%2F&e=abc",
                "https://business.instagram.com/getting-started",
                "https://applink.instagram.com/p/DAbCdEfGhIj/",
                "https://applink.instagram.com/reel/DAbCdEfGhIj/");
        bounce(
                "https://applink.instagram.com/explore/",
                "https://business.instagram.com/explore/tags/cats/",
                "https://l.instagram.com/reels/",
                "https://help.instagram.com/explore/../x",
                "https://anything.instagram.com/_n/mainfeed");
    }

    // ---- instagram:// -----------------------------------------------------------------------

    @Test
    public void appLinksToSingleItemsOpen() {
        allow(
                "instagram://media?id=3141592653589793238",
                "instagram://media?id=3141592653589793238_25025320",
                "instagram://user?username=some.user_1",
                "instagram://reel?id=3141592653589793238",
                "instagram://reels_share?shortcode=DAbCdEfGhIj",
                "instagram://story?story_id=1&user_id=2",
                "instagram://story-camera",
                "instagram://stories?username=some.user_1",
                "instagram://direct",
                "instagram://direct-inbox",
                "instagram://direct_thread?thread_id=340282366841710300949128137443944319108",
                "instagram://direct_v2?thread_id=1",
                "instagram://direct?username=some.user_1",
                "instagram://profile",
                "instagram://news",
                "instagram://camera",
                "instagram://library?AssetPath=x",
                "INSTAGRAM://MEDIA?id=1",
                "ig://media?id=1");
    }

    @Test
    public void appLinksForLoginAndAccountFlowsOpen() {
        allow(
                "instagram://emaillogin?uid=abc&token=def",
                "instagram://smslogin?uid=abc&token=def",
                "instagram://web_emaillogin?uid=abc",
                "instagram://login",
                "instagram://confirm_email?token=def",
                "instagram://two_factor_login",
                "instagram://challenge",
                "instagram://checkpoint",
                "instagram://password_reset?token=def",
                "instagram://accounts",
                "instagram://accountscenter",
                "instagram://settings",
                "instagram://editprofile");
    }

    @Test
    public void appLinksToFeedsBounce() {
        bounce(
                "instagram://",
                "instagram:",
                "instagram://feed",
                "instagram://mainfeed",
                "instagram://main_feed",
                "instagram://home",
                "instagram://app",
                "instagram://reels",
                "instagram://reels_home",
                "instagram://reels_tab",
                "instagram://reels_audio_page?audio_id=1",
                "instagram://clips",
                "instagram://clips_home",
                "instagram://clips_share?id=1",
                "instagram://explore",
                "instagram://explore_people",
                "instagram://discover_people",
                "instagram://search",
                "instagram://search?q=cats",
                "instagram://tag?name=cats",
                "instagram://hashtag?name=cats",
                "instagram://location?id=212988663",
                "instagram://topic?id=1",
                "instagram://audio?id=1",
                "instagram://igtv",
                "instagram://tv",
                "INSTAGRAM://EXPLORE",
                "Instagram://Reels",
                "ig://explore",
                "ig://mainfeed");
    }

    /** "instagram://reel" with nothing behind it names no reel. */
    @Test
    public void appItemLinksNeedAnItem() {
        bounce("instagram://reel", "instagram://reel?", "instagram://media", "instagram://user",
                "instagram://reels_share");
        allow("instagram://media/3141592653589793238", "instagram://user/some.user_1");
    }

    /** New routes appear with every release; one this table does not know fails closed. */
    @Test
    public void unknownAppHostsBounce() {
        bounce(
                "instagram://something_new",
                "instagram://something_new?id=1",
                "instagram://bloks?app_id=whatever",
                "instagram://shopping_home",
                "instagram://promote",
                "instagram://insights",
                "instagram:explore",
                "instagram:media?id=1");
    }

    /** No allow rule may let a feed in through its prefix or suffix. */
    @Test
    public void denyWordsBeatTheAllowedFamilies() {
        bounce(
                "instagram://direct_explore",
                "instagram://story_feed",
                "instagram://stories_home",
                "instagram://accounts_discover",
                "instagram://account_suggestions",
                "instagram://settings_search",
                "instagram://explore_login",
                "instagram://feed_login",
                "instagram://user_search?username=x",
                "instagram://media_feed?id=1");
    }

    @Test
    public void appLinkPathTricksBounce() {
        bounce("instagram://media/../explore", "instagram://user/./x", "instagram://media/a\\b");
    }

    // ---- "no link" and the parts API --------------------------------------------------------

    /**
     * The loop guard: the intent a bounce produces has no data URI, which reads as all-null
     * parts, and that must be ALLOW or a bounce would bounce itself forever.
     */
    @Test
    public void noLinkIsAllowed() {
        assertEquals(Verdict.ALLOW, PostboxLinks.classify(null, null, null, null));
        assertEquals(Verdict.ALLOW, PostboxLinks.classify("", "", "", ""));
        assertEquals(Verdict.ALLOW, PostboxLinks.classify((String) null));
        assertEquals(Verdict.ALLOW, PostboxLinks.classify((URI) null));
        allow("", "explore", "/explore/", "//www.instagram.com/explore/");
    }

    /** The parts as android.net.Uri hands them over: decoded path, raw query. */
    @Test
    public void classifiesFromParts() {
        assertEquals(Verdict.BOUNCE, PostboxLinks.classify("https", "www.instagram.com", "/explore/", null));
        assertEquals(Verdict.BOUNCE, PostboxLinks.classify("https", "www.instagram.com", "", null));
        assertEquals(Verdict.BOUNCE, PostboxLinks.classify("https", "www.instagram.com", null, null));
        assertEquals(Verdict.BOUNCE, PostboxLinks.classify("https", null, "/p/DAbCdEfGhIj/", null));
        assertEquals(Verdict.ALLOW, PostboxLinks.classify("https", "www.instagram.com", "/p/DAbCdEfGhIj/", "igsh=x"));
        assertEquals(Verdict.ALLOW, PostboxLinks.classify("HTTPS", " WWW.Instagram.COM ", "/reel/DAbCdEfGhIj", null));
        assertEquals(Verdict.BOUNCE, PostboxLinks.classify("instagram", "explore", null, null));
        assertEquals(Verdict.BOUNCE, PostboxLinks.classify("instagram", null, null, null));
        assertEquals(Verdict.BOUNCE, PostboxLinks.classify("instagram", "reel", "", ""));
        assertEquals(Verdict.ALLOW, PostboxLinks.classify("instagram", "reel", "", "id=1"));
        assertEquals(Verdict.ALLOW, PostboxLinks.classify("instagram", "direct_thread", "", "thread_id=1"));
        assertEquals(Verdict.ALLOW, PostboxLinks.classify("content", "media", "/external/images/1", null));
    }

    @Test
    public void javaNetUriIsAccepted() throws Exception {
        assertEquals(Verdict.BOUNCE, PostboxLinks.classify(new URI("https://www.instagram.com/explore/")));
        assertEquals(Verdict.ALLOW, PostboxLinks.classify(new URI("https://www.instagram.com/p/DAbCdEfGhIj/")));
        // java.net.URI reports no host for an authority with an underscore; the text is used instead.
        URI thread = new URI("instagram://direct_thread?thread_id=1");
        assertEquals(null, thread.getHost());
        assertEquals(Verdict.ALLOW, PostboxLinks.classify(thread));
        assertEquals(Verdict.BOUNCE, PostboxLinks.classify(new URI("instagram://reels_home")));
    }

    @Test
    public void splitMatchesAndroidUriSemantics() {
        assertArrayEquals(new String[] {"https", "www.instagram.com", "/p/x/", "a=b"},
                PostboxLinks.split("https://www.instagram.com/p/x/?a=b#frag"));
        assertArrayEquals(new String[] {"https", "www.instagram.com", "", null},
                PostboxLinks.split("https://www.instagram.com"));
        assertArrayEquals(new String[] {"https", "www.instagram.com", "/a b/", null},
                PostboxLinks.split("https://u:p@www.instagram.com:8443/a%20b/"));
        assertArrayEquals(new String[] {"instagram", "user", "", "username=x"},
                PostboxLinks.split("instagram://user?username=x"));
        // Opaque: no host and no path.
        assertArrayEquals(new String[] {"instagram", null, null, null}, PostboxLinks.split("instagram:explore"));
        assertArrayEquals(new String[] {"mailto", null, null, null}, PostboxLinks.split("mailto:a@b.c"));
        // No scheme: a colon after the first slash is not one.
        assertArrayEquals(new String[] {null, null, "/a:b", null}, PostboxLinks.split("/a:b"));
    }

    // ---- logging ----------------------------------------------------------------------------

    /** The bounce log line carries scheme, host and path, and never a query. */
    @Test
    public void descriptionHasNoQuery() {
        String[] parts = PostboxLinks.split("https://www.instagram.com/explore/tags/cats/?token=SECRET#SECRET");
        String description = PostboxLinks.describe(parts[0], parts[1], parts[2]);
        assertEquals("https://www.instagram.com/explore/tags/cats/", description);
        assertFalse(description.contains("SECRET"));
        assertEquals("instagram://explore", PostboxLinks.describe("instagram", "explore", null));
        assertEquals("://", PostboxLinks.describe(null, null, null));
    }

    @Test
    public void descriptionIsBoundedAndPrintable() {
        StringBuilder path = new StringBuilder("/explore/");
        for (int index = 0; index < 500; index++) path.append('x');
        assertEquals(160, PostboxLinks.describe("https", "www.instagram.com", path.toString()).length());
        assertEquals("https://h/a?b", PostboxLinks.describe("https", "h", "/a\nb"));
    }
}
