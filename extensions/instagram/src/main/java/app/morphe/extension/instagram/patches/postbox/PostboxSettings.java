package app.morphe.extension.instagram.patches.postbox;

import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;

import java.util.HashSet;
import java.util.Set;

import app.morphe.extension.instagram.constants.Constants;
import app.morphe.extension.instagram.patches.focusLock.FocusLock;
import app.morphe.extension.instagram.patches.focusLock.FocusLockTargets;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.settings.preference.widgets.NavigationBarPreference;
import app.morphe.extension.instagram.settings.preference.widgets.NavigationStartupPreference;

/**
 * Postbox (Vantage fork only): settings-screen hygiene for the prefs the "Postbox settings lock"
 * patch hardcodes.
 *
 * The lock itself is the patch rewriting the {@code Pref} getters to constants; a row left in
 * the settings screen could no longer change anything. This only removes those rows so the UI
 * does not offer dead toggles. It works on the finished screen, whatever built it, so piko's
 * ScreenBuilder can change freely: the patch swaps the one
 * {@code PreferenceFragment.setPreferenceScreen(screen)} call in piko's settings fragment for
 * {@link #setPreferenceScreen(PreferenceFragment, PreferenceScreen)}.
 *
 * Deliberately NOT done by clearing the {@code SettingsStatus} flags that gate the rows: the
 * same flags gate the blocking code ({@code Links}, {@code Block}, {@code SuggestedProfiles}).
 */
@SuppressWarnings({"unused", "deprecation"})
public final class PostboxSettings {
    private PostboxSettings() {
    }

    /** Drop-in for {@code fragment.setPreferenceScreen(screen)} that prunes the screen first. */
    public static void setPreferenceScreen(PreferenceFragment fragment, PreferenceScreen screen) {
        try {
            prune(screen, lockedKeys());
        } catch (Throwable ignored) {
            // Hygiene only. A row that survives is a dead toggle, not a way around the lock.
        }
        fragment.setPreferenceScreen(screen);
    }

    /**
     * Keys of the rows to remove. Built from piko's own constants so an upstream rename breaks
     * the compile instead of silently leaving a row behind.
     */
    private static Set<String> lockedKeys() {
        String[] forcedSwitches = {
                Settings.HIDE_SUGGESTED_CONTENT.key,
                Settings.DISABLE_EXPLORE.key,
                Settings.DISABLE_DISCOVER_PEOPLE.key,
                Settings.DISABLE_REELS_SCROLLING.key,
                Settings.DISABLE_SWIPE_TO_CREATE.key,
        };
        Set<String> keys = new HashSet<>();
        for (String key : forcedSwitches) {
            keys.add(key);
            // The same switch offered as a Focus Lock target would be just as dead.
            keys.add(FocusLock.SELECTION_PREFIX + key);
        }
        keys.add(FocusLock.SELECTION_PREFIX + FocusLockTargets.REELS_TAB_KEY);
        // Root-page entry that opens the navigation screen (tab editor + startup tab picker).
        keys.add(Constants.PIKO_FRAGMENT_NAV_BTNS);
        return keys;
    }

    private static void prune(PreferenceGroup group, Set<String> lockedKeys) {
        if (group == null) return;
        for (int index = group.getPreferenceCount() - 1; index >= 0; index--) {
            Preference preference = group.getPreference(index);
            if (isLocked(preference, lockedKeys)) {
                group.removePreference(preference);
            } else if (preference instanceof PreferenceGroup) {
                prune((PreferenceGroup) preference, lockedKeys);
            }
        }
    }

    private static boolean isLocked(Preference preference, Set<String> lockedKeys) {
        // The navigation widgets carry no key, and the screen holding them can also be opened
        // directly by fragment name, so they are matched by type.
        if (preference instanceof NavigationBarPreference
                || preference instanceof NavigationStartupPreference) {
            return true;
        }
        String key = preference.getKey();
        return key != null && lockedKeys.contains(key);
    }
}
