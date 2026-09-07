package app.morphe.extension.twitter.patches.timelimit;

import java.util.TimeZone;

/**
 * The three time sources the engine needs, abstracted so the pure logic can be
 * unit-tested with a fake and so the build-time test hook can offset them.
 */
public interface Clock {
    /** Wall clock, epoch millis (System.currentTimeMillis). Can be tampered with. */
    long wallMs();

    /** Monotonic millis since boot (SystemClock.elapsedRealtime). Immune to clock edits. */
    long elapsedMs();

    /** Settings.Global.BOOT_COUNT, so elapsed values from different boots are never compared. */
    long bootCount();

    /** The phone's current zone; the 5am reset is evaluated in it. */
    TimeZone zone();
}
