package app.morphe.extension.instagram.patches.postbox;

/**
 * Postbox (Vantage fork only): the build-time switches, one per Postbox patch.
 *
 * Every getter here is {@code return false;} in source and compiles to exactly
 * {@code const/4 v0, 0x0; return v0}. The patch that owns a switch rewrites that constant to 1
 * in the extension dex at patch time (same idea as the X {@code BuildValues}), so:
 *
 * - a bundle user who enables one Postbox patch gets that one feature and nothing else,
 * - with no Postbox patch applied every getter is false and the Postbox code does nothing,
 * - nothing at runtime (a preference, a system property, an intent) can flip any of them.
 *
 * Keep each method a bare constant return. The patch refuses to build if a getter has any other
 * shape, and PostboxBuildTest pins the false defaults.
 */
public final class PostboxBuild {
    private PostboxBuild() {
    }

    /** "Postbox shortcut lock": clear dynamic shortcuts and disable pinned ones. */
    public static boolean shortcutLock() {
        return false;
    }

    /** "Postbox link router": bounce forbidden deep links and startup tabs to the inbox. */
    public static boolean linkRouter() {
        return false;
    }

    /** "Postbox network block": block the feed, Explore, Reels-tab and chaining endpoints. */
    public static boolean networkBlock() {
        return false;
    }

    /**
     * "Postbox network block", option {@code traceNetwork}: log every request path to logcat.
     * For endpoint discovery on a verification build ONLY. The release build script refuses any
     * enabled option whose key contains "trace", and nothing but the patch can set this.
     */
    public static boolean traceNetwork() {
        return false;
    }
}
