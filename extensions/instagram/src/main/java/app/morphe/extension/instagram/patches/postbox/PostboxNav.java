package app.morphe.extension.instagram.patches.postbox;

import android.content.Intent;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Postbox (Vantage fork only): the fixed bottom bar and the startup-tab guard.
 *
 * Nothing here reads a preference. The "Postbox settings lock" patch bakes
 * {@link #NAV_CONFIG} into {@code Pref.navigationTabs()} and calls
 * {@link #sanitizeStartupIntent(Intent)} first thing in piko's startup seam, so no stored
 * setting, backup restore or Focus Lock state can change either. It also runs every tab list
 * piko hands back to Instagram through {@link #filterNavigationTabs(List)}, so the bar stays
 * locked on the paths where piko itself gives up and returns Instagram's own list.
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

    /**
     * Tabs the bottom bar may contain, as Instagram enum names: Home, Messages, Profile. The
     * same three {@link #NAV_CONFIG} makes visible; PostboxNavFilterTest ties the two together.
     */
    private static final Set<String> ALLOWED_BAR_TABS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("FEED", "DIRECT", "PROFILE")));

    private PostboxNav() {
    }

    /**
     * Pure: the entries of a tab list that the bar may show, in their original order. An entry
     * is kept only if it is an enum constant whose name is on the allowlist; anything else,
     * including null and values that are not enums at all, is dropped.
     */
    static List<Object> keepAllowedTabs(List<Object> tabs) {
        List<Object> kept = new ArrayList<>(tabs.size());
        for (Object tab : tabs) {
            if (tab instanceof Enum<?> && ALLOWED_BAR_TABS.contains(((Enum<?>) tab).name())) {
                kept.add(tab);
            }
        }
        return kept;
    }

    /**
     * Pure: the list the bar gets, given the list piko produced and what survived the allowlist.
     * Nothing dropped: piko's own list, untouched. Something dropped: the survivors. Everything
     * dropped: piko's list again, because an empty bar would leave the app with no way to
     * navigate at all, which is worse than a bar with a tab too many.
     */
    static List<Object> chooseBarTabs(List<Object> tabs, List<Object> kept) {
        return kept.isEmpty() || kept.size() == tabs.size() ? tabs : kept;
    }

    /**
     * Fail-closed filter on the result of piko's {@code NavigationBarPatch.transformNavigationTabs}.
     * The patch routes every {@code return} of that method through here.
     *
     * Piko fails OPEN by design: on any internal exception, or on a native list it cannot work
     * with, it returns Instagram's own tabs, Reels and Search included. This drops every entry
     * that is not Home, Messages or Profile from whatever comes back. On piko's normal path the
     * result already is exactly those three, in {@link #NAV_CONFIG} order, and passes through
     * as the same list object; the filter never reorders and never adds.
     *
     * Runs inside Instagram's tab setup, so it must never throw.
     */
    public static List<Object> filterNavigationTabs(List<Object> tabs) {
        List<Object> chosen = tabs;
        try {
            if (tabs == null || tabs.isEmpty()) return tabs;
            List<Object> kept = keepAllowedTabs(tabs);
            chosen = chooseBarTabs(tabs, kept);
            if (kept.isEmpty()) {
                Log.e("Postbox", "nav filter: none of " + tabs + " is a Postbox tab. "
                        + "The bar is left UNFILTERED rather than empty; Instagram renamed its tabs");
            } else if (chosen != tabs) {
                Log.w("Postbox", "nav filter: " + tabs + " -> " + chosen);
            }
        } catch (Throwable ignored) {
            // Whatever was decided before the failure stands.
        }
        return chosen;
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
