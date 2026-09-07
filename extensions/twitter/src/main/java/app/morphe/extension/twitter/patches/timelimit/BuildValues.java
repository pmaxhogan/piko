package app.morphe.extension.twitter.patches.timelimit;

/**
 * Values the Kotlin patch rewrites at patch time, one per build variant. Keep
 * these as trivial methods returning constants: the patch replaces each method
 * body with a single const/return, which is obfuscation-independent.
 *
 * Defaults here are the most permissive legal build (2h) with test hooks OFF,
 * so an unpatched extension still behaves like a release build.
 */
public final class BuildValues {
    private BuildValues() {}

    /** Highest daily limit the user may configure in this build, in minutes. */
    public static int maxLimitMinutes() {
        return 120;
    }

    /**
     * Whether the debug clock offset (adb `setprop debug.vantagex.offset_ms`) is
     * honored. Compiled to false in every release variant; only the verification
     * build turns it on, so it cannot be a bypass vector for shipped APKs.
     */
    public static boolean testHooksEnabled() {
        return false;
    }
}
