package app.morphe.extension.instagram.patches.postbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    /** The inbox as a link is allowed too, in every form Instagram has for it. */
    @Test
    public void theInboxItselfIsAlwaysReachable() {
        assertEquals(Verdict.ALLOW, PostboxLinks.classify("https://www.instagram.com/direct/inbox/"));
        assertEquals(Verdict.ALLOW, PostboxLinks.classify("instagram://direct-inbox"));
        assertEquals(Verdict.ALLOW, PostboxLinks.classify("instagram://direct"));
    }
}
