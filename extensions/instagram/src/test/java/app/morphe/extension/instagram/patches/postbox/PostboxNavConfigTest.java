package app.morphe.extension.instagram.patches.postbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import app.morphe.extension.instagram.patches.navigation.NavigationBarPatch;
import app.morphe.extension.instagram.patches.navigation.NavigationBarPatch.Config;
import app.morphe.extension.instagram.patches.navigation.NavigationBarPatch.Tab;

/**
 * Runs {@link PostboxNav#NAV_CONFIG} through piko's REAL navigation parser, not a copy of it.
 *
 * {@code NavigationBarPatch.loadConfig()} itself reads SharedPreferences, so it cannot run on a
 * plain JVM; its pure core ({@code resolveConfig}, {@code encodeConfig}, {@code startupChoices})
 * can, and is reached by reflection because upstream keeps it private. If upstream renames or
 * reshapes those methods this test fails, which is the point: the fixed layout has to be
 * re-verified against the new parser before a Postbox build ships.
 */
public class PostboxNavConfigTest {
    private static final Set<Tab> NO_LEGACY = Collections.emptySet();

    private static Config resolve(String stored, boolean legacyPresent, Set<Tab> legacyVisible)
            throws Exception {
        Method method = NavigationBarPatch.class.getDeclaredMethod(
                "resolveConfig", String.class, boolean.class, Set.class);
        method.setAccessible(true);
        Config config = (Config) method.invoke(null, stored, legacyPresent, legacyVisible);
        assertNotNull(config);
        return config;
    }

    private static String encode(Config config) throws Exception {
        Method method = NavigationBarPatch.class.getDeclaredMethod("encodeConfig", Config.class);
        method.setAccessible(true);
        return (String) method.invoke(null, config);
    }

    private static List<Tab> startupChoices(Config config) throws Exception {
        Method method = NavigationBarPatch.class.getDeclaredMethod("startupChoices", Config.class);
        method.setAccessible(true);
        return Arrays.asList((Tab[]) method.invoke(null, config));
    }

    /** The bar as rendered: configured order, visible tabs only. */
    private static List<Tab> bar(Config config) {
        List<Tab> bar = new ArrayList<>();
        for (Tab tab : config.order()) {
            if (config.visible().contains(tab)) bar.add(tab);
        }
        return bar;
    }

    @Test
    public void barIsExactlyHomeMessagesProfile() throws Exception {
        Config config = resolve(PostboxNav.NAV_CONFIG, false, NO_LEGACY);
        assertEquals(Arrays.asList(Tab.HOME, Tab.MESSAGES, Tab.PROFILE), bar(config));
        assertEquals(EnumSet.of(Tab.HOME, Tab.MESSAGES, Tab.PROFILE), config.visible());
    }

    @Test
    public void startupTabIsMessages() throws Exception {
        Config config = resolve(PostboxNav.NAV_CONFIG, false, NO_LEGACY);
        assertEquals(Tab.MESSAGES, config.startup());
        // What piko's cold-start path tries, in order: Messages first, never Reels or Search.
        assertEquals(Arrays.asList(Tab.MESSAGES, Tab.HOME, Tab.PROFILE), startupChoices(config));
    }

    /**
     * The constant is already in piko's canonical encoding. loadConfig() writes the stored
     * value back whenever it differs from the re-encoded config, so a string that did not
     * round-trip would be "corrected" on every load (harmlessly, since the getter is constant,
     * but it would mean piko reads the layout differently from how it is written here).
     */
    @Test
    public void constantIsPikosCanonicalEncoding() throws Exception {
        Config config = resolve(PostboxNav.NAV_CONFIG, false, NO_LEGACY);
        assertEquals(PostboxNav.NAV_CONFIG, encode(config));
    }

    /** With a stored v2 config, piko's legacy (pre-v2) settings cannot change the result. */
    @Test
    public void legacySettingsCannotOverrideTheConstant() throws Exception {
        Config config = resolve(PostboxNav.NAV_CONFIG, true, EnumSet.allOf(Tab.class));
        assertEquals(Arrays.asList(Tab.HOME, Tab.MESSAGES, Tab.PROFILE), bar(config));
        assertEquals(Tab.MESSAGES, config.startup());
        assertEquals(PostboxNav.NAV_CONFIG, encode(config));
    }

    /**
     * An independent encoding built from the Tab keys alone, without piko's encoder: guards the
     * test above against an encoder change that happened to keep the round trip intact.
     */
    @Test
    public void constantMatchesAnIndependentEncoding() {
        Set<Tab> visible = EnumSet.of(Tab.HOME, Tab.MESSAGES, Tab.PROFILE);
        Tab[] order = {
                Tab.HOME, Tab.REELS, Tab.MESSAGES, Tab.SEARCH, Tab.PROFILE, Tab.CREATE, Tab.NOTIFICATIONS
        };
        StringBuilder expected = new StringBuilder("v2|");
        for (int index = 0; index < order.length; index++) {
            if (index > 0) expected.append(',');
            expected.append(order[index].key()).append(':').append(visible.contains(order[index]) ? '1' : '0');
        }
        expected.append('|').append(Tab.MESSAGES.key());
        assertEquals(expected.toString(), PostboxNav.NAV_CONFIG);
        assertEquals(Tab.values().length, order.length);
    }

    /** Sanity check on the test itself: the real parser does show Reels and Search by default. */
    @Test
    public void emptyStoredValueWouldShowReelsAndSearch() throws Exception {
        Config config = resolve("", false, NO_LEGACY);
        assertEquals(
                Arrays.asList(Tab.HOME, Tab.REELS, Tab.MESSAGES, Tab.SEARCH, Tab.PROFILE),
                bar(config));
    }
}
