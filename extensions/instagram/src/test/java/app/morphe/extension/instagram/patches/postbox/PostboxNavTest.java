package app.morphe.extension.instagram.patches.postbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.Set;

import org.junit.Test;

import app.morphe.extension.instagram.patches.navigation.NavigationBarPatch.Tab;

/** The startup-tab allowlist (L3): what an intent may ask for, and what happens to the rest. */
public class PostboxNavTest {
    private static final String INBOX = "DIRECT";

    /** Tabs Postbox removes; an intent must never be able to land on them. */
    private static final Set<Tab> FORBIDDEN = EnumSet.of(Tab.REELS, Tab.SEARCH);

    @Test
    public void allowedTabsPassThroughUnchanged() {
        for (String tab : new String[] {"FEED", "DIRECT", "PROFILE", "NEWS", "SHARE", "CREATION"}) {
            assertEquals(tab, PostboxNav.sanitizeStartupTab(tab));
        }
    }

    @Test
    public void reelsAndSearchBounceToTheInbox() {
        assertEquals(INBOX, PostboxNav.sanitizeStartupTab("CLIPS"));
        assertEquals(INBOX, PostboxNav.sanitizeStartupTab("SEARCH"));
    }

    @Test
    public void nullAndEmptyFailClosed() {
        // null is what an extra of the wrong type reads as.
        assertEquals(INBOX, PostboxNav.sanitizeStartupTab(null));
        assertEquals(INBOX, PostboxNav.sanitizeStartupTab(""));
        assertEquals(INBOX, PostboxNav.sanitizeStartupTab(" "));
    }

    @Test
    public void matchingIsExactAndCaseSensitive() {
        for (String value : new String[] {
                "feed", "Feed", "direct", "profile", "clips", "search",
                "FEED ", " FEED", "FEED\n", "FEED,CLIPS", "FEED|CLIPS", "CLIPS\0FEED"
        }) {
            assertEquals("for [" + value + "]", INBOX, PostboxNav.sanitizeStartupTab(value));
        }
    }

    @Test
    public void unknownNamesFailClosed() {
        // Piko's own tab keys and labels are not Instagram enum names, and neither is a name a
        // future Instagram build might add.
        for (String value : new String[] {
                "HOME", "REELS", "MESSAGES", "EXPLORE", "NOTIFICATIONS", "CREATE",
                "fragment_clips", "fragment_search", "CLIPS_V2", "FRIENDS_MAP", "garbage", "0"
        }) {
            assertEquals("for [" + value + "]", INBOX, PostboxNav.sanitizeStartupTab(value));
        }
    }

    @Test
    public void resultIsAlwaysAnAllowedTab() {
        for (String value : new String[] {null, "", "CLIPS", "SEARCH", "FEED", "NEWS", "x"}) {
            String result = PostboxNav.sanitizeStartupTab(value);
            assertEquals(result, PostboxNav.sanitizeStartupTab(result));
        }
    }

    /**
     * Ties the allowlist to piko's table of Instagram enum names: every name piko knows for a
     * removed tab is bounced, every name for a tab Postbox keeps is let through, and the bounce
     * target really is the Messages tab. Fails if upstream teaches piko a new name.
     */
    @Test
    public void allowlistAgreesWithPikoTabNames() throws Exception {
        for (Tab tab : Tab.values()) {
            for (String enumName : enumNames(tab)) {
                String expected = FORBIDDEN.contains(tab) ? INBOX : enumName;
                assertEquals(tab + " as " + enumName, expected, PostboxNav.sanitizeStartupTab(enumName));
            }
        }
        assertTrue(enumNames(Tab.MESSAGES).contains(PostboxNav.INBOX_TAB));
        assertEquals(INBOX, PostboxNav.INBOX_TAB);
        assertFalse(enumNames(Tab.REELS).isEmpty());
        assertFalse(enumNames(Tab.SEARCH).isEmpty());
    }

    @Test
    public void startupExtraNameMatchesPiko() throws Exception {
        Field extra = Class.forName("app.morphe.extension.instagram.patches.navigation.NavigationBarPatch")
                .getDeclaredField("STARTUP_TAB_EXTRA");
        extra.setAccessible(true);
        assertEquals(extra.get(null), PostboxNav.STARTUP_TAB_EXTRA);
    }

    @Test
    public void sanitizeStartupIntentToleratesNull() {
        PostboxNav.sanitizeStartupIntent(null);
    }

    /** The patch reads NAV_CONFIG as a dex constant, which needs a public static final String. */
    @Test
    public void navConfigIsAConstantField() throws Exception {
        Field field = PostboxNav.class.getField("NAV_CONFIG");
        int modifiers = field.getModifiers();
        assertTrue(Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers));
        assertEquals(String.class, field.getType());
    }

    @SuppressWarnings("unchecked")
    private static Set<String> enumNames(Tab tab) throws Exception {
        Field field = Tab.class.getDeclaredField("enumNames");
        field.setAccessible(true);
        return (Set<String>) field.get(tab);
    }
}
