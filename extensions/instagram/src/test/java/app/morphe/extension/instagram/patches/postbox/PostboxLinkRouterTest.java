package app.morphe.extension.instagram.patches.postbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import app.morphe.extension.instagram.patches.postbox.PostboxLinks.Verdict;

/** The pure parts of the bounce: the relaunch loop guard, and where a bounce lands. */
public class PostboxLinkRouterTest {
    @Test
    public void firstBounceMayRelaunch() {
        assertTrue(PostboxLinkRouter.relaunchAllowed(0, Postbox.NEVER));
        assertTrue(PostboxLinkRouter.relaunchAllowed(987_654_321L, Postbox.NEVER));
    }

    /**
     * A bounce right after a relaunch means the relaunched activity was bounced as well. It must
     * not relaunch again, or the app would restart forever.
     */
    @Test
    public void bounceRightAfterARelaunchStaysInPlace() {
        long last = 1_000_000L;
        assertFalse(PostboxLinkRouter.relaunchAllowed(last, last));
        assertFalse(PostboxLinkRouter.relaunchAllowed(last + 200, last));
        assertFalse(PostboxLinkRouter.relaunchAllowed(last + PostboxLinkRouter.RELAUNCH_GUARD_MS - 1, last));
    }

    @Test
    public void laterBounceMayRelaunchAgain() {
        long last = 1_000_000L;
        assertTrue(PostboxLinkRouter.relaunchAllowed(last + PostboxLinkRouter.RELAUNCH_GUARD_MS, last));
        assertTrue(PostboxLinkRouter.relaunchAllowed(last + 60_000, last));
    }

    /**
     * A bounce must not itself be bounced. What a bounce produces is an intent with no data URI
     * and the inbox as startup tab; both halves of the router have to accept exactly that.
     */
    @Test
    public void theResultOfABounceIsNeverBounced() {
        assertEquals(Verdict.ALLOW, PostboxLinks.classify(null, null, null, null));
        assertEquals(PostboxNav.INBOX_TAB, PostboxNav.sanitizeStartupTab(PostboxNav.INBOX_TAB));
    }

    /**
     * A reel link is handed on as a post link (in place, or sent to the app again). What is
     * handed on must pass straight through the router: allowed, and not a reel link, or the
     * router would send it round forever.
     */
    @Test
    public void theResultOfAReelRewritePassesStraightThrough() {
        String post = PostboxLinks.reelAsPostLink("https://www.instagram.com/reel/DAbCdEfGhIj/?igsh=x");
        assertEquals("https://www.instagram.com/p/DAbCdEfGhIj/", post);
        assertEquals(Verdict.ALLOW, PostboxLinks.classify(post));
        assertNull(PostboxLinks.reelAsPostLink(post));
        // The activity the link is taken away from keeps no link at all, which is allowed too.
        assertNull(PostboxLinks.reelAsPostLink(null, null, null));
    }

    /** The Reels tab itself is still a bounce, not a rewrite. */
    @Test
    public void bareReelsIsStillBounced() {
        assertNull(PostboxLinks.reelAsPostLink("https://www.instagram.com/reels/"));
        assertEquals(Verdict.BOUNCE, PostboxLinks.classify("https://www.instagram.com/reels/"));
    }

    /** The inbox as a link is allowed too, in every form Instagram has for it. */
    @Test
    public void theInboxItselfIsAlwaysReachable() {
        assertEquals(Verdict.ALLOW, PostboxLinks.classify("https://www.instagram.com/direct/inbox/"));
        assertEquals(Verdict.ALLOW, PostboxLinks.classify("instagram://direct-inbox"));
        assertEquals(Verdict.ALLOW, PostboxLinks.classify("instagram://direct"));
    }
}
