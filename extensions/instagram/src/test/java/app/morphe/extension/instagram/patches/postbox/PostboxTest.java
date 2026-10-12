package app.morphe.extension.instagram.patches.postbox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The throttle decision the Postbox features share. */
public class PostboxTest {
    private static final long MINUTE = 60_000L;

    @Test
    public void neverHappenedIsAlwaysDue() {
        assertTrue(Postbox.intervalElapsed(0, Postbox.NEVER, MINUTE));
        assertTrue(Postbox.intervalElapsed(123_456_789L, Postbox.NEVER, MINUTE));
        // No overflow from subtracting Long.MIN_VALUE.
        assertTrue(Postbox.intervalElapsed(Long.MAX_VALUE, Postbox.NEVER, MINUTE));
    }

    @Test
    public void notDueInsideTheInterval() {
        assertFalse(Postbox.intervalElapsed(1_000, 1_000, MINUTE));
        assertFalse(Postbox.intervalElapsed(1_001, 1_000, MINUTE));
        assertFalse(Postbox.intervalElapsed(1_000 + MINUTE - 1, 1_000, MINUTE));
    }

    @Test
    public void dueFromTheIntervalOn() {
        assertTrue(Postbox.intervalElapsed(1_000 + MINUTE, 1_000, MINUTE));
        assertTrue(Postbox.intervalElapsed(1_000 + MINUTE + 1, 1_000, MINUTE));
        assertTrue(Postbox.intervalElapsed(1_000 + 10 * MINUTE, 1_000, MINUTE));
    }

    /** A timestamp from the future must not be able to hold the check off. */
    @Test
    public void clockBehindTheLastTimeIsDue() {
        assertTrue(Postbox.intervalElapsed(999, 1_000, MINUTE));
        assertTrue(Postbox.intervalElapsed(0, Long.MAX_VALUE, MINUTE));
    }

    @Test
    public void zeroIntervalIsAlwaysDue() {
        assertTrue(Postbox.intervalElapsed(1_000, 1_000, 0));
    }

    /** With no feature switched on (the source defaults), init must be a no-op that never throws. */
    @Test
    public void initWithEverythingOffDoesNothing() {
        Postbox.init(null);
        Postbox.init(null);
    }
}
