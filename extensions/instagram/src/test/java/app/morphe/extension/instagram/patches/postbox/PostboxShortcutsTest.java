package app.morphe.extension.instagram.patches.postbox;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import org.junit.Test;

/** The pure decisions of the shortcut lock (L1): what may be published, when to re-check, what to disable. */
public class PostboxShortcutsTest {
    @Test
    public void recheckIntervalIsOneMinute() {
        assertEquals(60_000L, PostboxShortcuts.RECHECK_MS);
    }

    @Test
    public void firstResumeAlwaysChecks() {
        assertTrue(PostboxShortcuts.shouldRecheck(0, Postbox.NEVER));
        assertTrue(PostboxShortcuts.shouldRecheck(5_000, Postbox.NEVER));
    }

    @Test
    public void resumesInsideTheMinuteAreSkipped() {
        long last = 1_000_000L;
        assertFalse(PostboxShortcuts.shouldRecheck(last, last));
        assertFalse(PostboxShortcuts.shouldRecheck(last + 1, last));
        assertFalse(PostboxShortcuts.shouldRecheck(last + 30_000, last));
        assertFalse(PostboxShortcuts.shouldRecheck(last + 59_999, last));
    }

    @Test
    public void resumeAfterTheMinuteChecksAgain() {
        long last = 1_000_000L;
        assertTrue(PostboxShortcuts.shouldRecheck(last + 60_000, last));
        assertTrue(PostboxShortcuts.shouldRecheck(last + 60_001, last));
        assertTrue(PostboxShortcuts.shouldRecheck(last + 3_600_000, last));
    }

    @Test
    public void bogusTimestampCannotSwitchTheLockOff() {
        assertTrue(PostboxShortcuts.shouldRecheck(10, 1_000_000L));
    }

    @Test
    public void onlyEnabledMutablePinnedShortcutsAreDisabled() {
        assertTrue(PostboxShortcuts.shouldDisable(true, false));
        // Already disabled: nothing to do.
        assertFalse(PostboxShortcuts.shouldDisable(false, false));
        // Manifest shortcuts are immutable; ShortcutManager throws if an app touches one.
        assertFalse(PostboxShortcuts.shouldDisable(true, true));
        assertFalse(PostboxShortcuts.shouldDisable(false, true));
    }

    /**
     * L1: no dynamic shortcut survives, whatever its categories. That includes the conversation
     * targets; keeping those is a product decision that has not been made.
     */
    @Test
    public void noDynamicShortcutIsKept() {
        assertFalse(PostboxShortcuts.keeps(null));
        assertFalse(PostboxShortcuts.keeps(Collections.emptySet()));
        assertFalse(PostboxShortcuts.keeps(Collections.singleton("actions.intent.SEND_MESSAGE")));
        assertFalse(PostboxShortcuts.keeps(Collections.singleton("android.shortcut.conversation")));
        assertFalse(PostboxShortcuts.keeps(new HashSet<>(Arrays.asList(
                "actions.intent.SEND_MESSAGE", "android.shortcut.conversation"))));
    }

    /** One pass at once, then about 1 s, 5 s and 15 s after the UI went to the background. */
    @Test
    public void backgroundPassesAreNowThenThreeDelayedOnes() {
        assertArrayEquals(new long[] {0L, 1_000L, 5_000L, 15_000L}, PostboxShortcuts.BACKGROUND_PASSES_MS);
    }

    @Test
    public void disabledMessageNamesPostbox() {
        assertEquals("Not available in Postbox", PostboxShortcuts.DISABLED_MESSAGE);
    }
}
