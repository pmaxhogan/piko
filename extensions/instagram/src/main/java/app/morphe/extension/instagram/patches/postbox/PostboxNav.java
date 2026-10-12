package app.morphe.extension.instagram.patches.postbox;

import android.content.Intent;
import android.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Postbox (Vantage fork only): the fixed bottom bar and the startup-tab guard.
 *
 * Nothing here reads a preference. The "Postbox settings lock" patch bakes
 * {@link #NAV_CONFIG} into {@code Pref.navigationTabs()} and calls
 * {@link #sanitizeStartupIntent(Intent)} first thing in piko's startup seam, so no stored
 * setting, backup restore or Focus Lock state can change either.
 */
@SuppressWarnings("unused")
public final class PostboxNav {
    /**
     * The one and only navigation layout: Home, Messages, Profile in the bar, Messages as the
     * startup tab. Format is piko's {@code NavigationBarPatch} config ("v2|key:visible,...|startup").
     *
     * This is the single source of the string. The patch reads this field's static value out of
     * the extension dex at patch time, so it MUST stay a compile-time constant (one literal, no
     * method call) or the patch fails the build. It must also stay in piko's canonical encoding
     * (every tab listed, default order): piko rewrites a stored value that does not round-trip,
     * and PostboxNavConfigTest pins that against the real parser.
     */
    public static final String NAV_CONFIG =
            "v2|home:1,reels:0,messages:1,search:0,profile:1,create:0,notifications:0|messages";

    /** Intent extra Instagram reads to pick the tab an activity start lands on. */
    public static final String STARTUP_TAB_EXTRA = "MainActivityAccountHelper.STARTUP_TAB";

    /**
     * Instagram's enum name for the Messages tab (piko: {@code NavigationBarPatch.Tab.MESSAGES}).
     * The bounce target for every forbidden or unknown startup tab.
     */
    public static final String INBOX_TAB = "DIRECT";

    /**
     * Startup tabs an intent may ask for, as Instagram enum names: Home (stories tray over an
     * empty feed), Messages, Profile, the notifications screen and the create flow (two aliases).
     * Everything else - CLIPS (Reels), SEARCH (Explore) and any name this build does not know -
     * is replaced with {@link #INBOX_TAB}.
     */
    private static final Set<String> ALLOWED_STARTUP_TABS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("FEED", "DIRECT", "PROFILE", "NEWS", "SHARE", "CREATION")));

    private PostboxNav() {
    }

    /**
     * The startup tab to actually use for a requested one. Exact, case-sensitive match against
     * the allowlist; null, empty, padded, lowercase and unknown values all fail closed to the
     * inbox.
     */
    public static String sanitizeStartupTab(String requested) {
        return requested != null && ALLOWED_STARTUP_TABS.contains(requested)
                ? requested
                : INBOX_TAB;
    }

    /**
     * Overwrites a forbidden startup-tab extra in place. An intent without the extra is left
     * alone: piko's cold-start path then applies the startup tab from {@link #NAV_CONFIG}.
     * An extra that is present but not a String reads as null and is overwritten too.
     *
     * Called from outside every try block in the startup path, so it must never throw.
     */
    public static void sanitizeStartupIntent(Intent intent) {
        try {
            if (intent == null || !intent.hasExtra(STARTUP_TAB_EXTRA)) return;
            String requested = intent.getStringExtra(STARTUP_TAB_EXTRA);
            String allowed = sanitizeStartupTab(requested);
            if (allowed.equals(requested)) return;
            intent.putExtra(STARTUP_TAB_EXTRA, allowed);
            Log.i("Postbox", "startup tab " + requested + " -> " + allowed);
        } catch (Throwable ignored) {
            // Extras that cannot be read cannot name a tab either; keep Instagram's own handling.
        }
    }
}
