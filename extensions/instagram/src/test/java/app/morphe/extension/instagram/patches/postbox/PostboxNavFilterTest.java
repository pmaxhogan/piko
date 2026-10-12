package app.morphe.extension.instagram.patches.postbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
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
 * The fail-closed bottom-bar filter: what survives when piko hands back a list that is not the
 * Postbox bar (its fail-open paths return Instagram's own tabs).
 */
public class PostboxNavFilterTest {
    /** Stand-in for Instagram's tab enum: same constant names, native bar order first. */
    private enum NativeTab { FEED, CLIPS, DIRECT, SEARCH, PROFILE, NEWS, SHARE, CREATION, FRIENDS_MAP }

    /** Another enum type with an allowed name: the filter goes by name, as piko does. */
    private enum OtherTab { PROFILE, DIRECT }

    private static List<Object> list(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static List<Object> filter(List<Object> tabs) {
        return PostboxNav.chooseBarTabs(tabs, PostboxNav.keepAllowedTabs(tabs));
    }

    @Test
    public void nativeBarLosesReelsAndSearch() {
        List<Object> nativeBar = list(
                NativeTab.FEED, NativeTab.CLIPS, NativeTab.DIRECT, NativeTab.SEARCH, NativeTab.PROFILE);
        assertEquals(list(NativeTab.FEED, NativeTab.DIRECT, NativeTab.PROFILE), filter(nativeBar));
    }

    @Test
    public void everyOtherTabIsDroppedToo() {
        List<Object> everything = list((Object[]) NativeTab.values());
        assertEquals(list(NativeTab.FEED, NativeTab.DIRECT, NativeTab.PROFILE), filter(everything));
    }

    /** Piko's normal result is already the Postbox bar: same object back, nothing copied. */
    @Test
    public void thePostboxBarPassesThroughAsTheSameList() {
        List<Object> bar = list(NativeTab.FEED, NativeTab.DIRECT, NativeTab.PROFILE);
        assertSame(bar, filter(bar));
    }

    /** The filter only removes: whatever order piko chose is kept. */
    @Test
    public void orderIsNeverChanged() {
        List<Object> reordered = list(
                NativeTab.PROFILE, NativeTab.SEARCH, NativeTab.DIRECT, NativeTab.CLIPS, NativeTab.FEED);
        assertEquals(list(NativeTab.PROFILE, NativeTab.DIRECT, NativeTab.FEED), filter(reordered));
        List<Object> allowedReordered = list(NativeTab.DIRECT, NativeTab.FEED, NativeTab.PROFILE);
        assertSame(allowedReordered, filter(allowedReordered));
    }

    @Test
    public void entriesThatAreNotEnumsAreDropped() {
        List<Object> mixed = list("FEED", NativeTab.FEED, null, 7, new Object(), NativeTab.DIRECT, "DIRECT");
        assertEquals(list(NativeTab.FEED, NativeTab.DIRECT), filter(mixed));
    }

    /** An empty bar would brick the app: the input comes back unchanged instead. */
    @Test
    public void nothingAllowedReturnsTheInputUnchanged() {
        List<Object> renamed = list(NativeTab.CLIPS, NativeTab.SEARCH, NativeTab.NEWS);
        assertSame(renamed, filter(renamed));
        List<Object> junk = list("FEED", "DIRECT", null);
        assertSame(junk, filter(junk));
        assertTrue(PostboxNav.keepAllowedTabs(renamed).isEmpty());
    }

    @Test
    public void emptyAndNullListsAreReturnedAsTheyAre() {
        List<Object> empty = new ArrayList<>();
        assertSame(empty, filter(empty));
        // The public wrapper returns before it has anything to log for these.
        assertSame(empty, PostboxNav.filterNavigationTabs(empty));
        assertEquals(null, PostboxNav.filterNavigationTabs(null));
    }

    @Test
    public void matchingIsByExactEnumName() {
        assertEquals(list(OtherTab.PROFILE, OtherTab.DIRECT), filter(list(OtherTab.PROFILE, OtherTab.DIRECT)));
        // A filtered list is a new list, never the caller's with entries removed.
        List<Object> nativeBar = list(NativeTab.FEED, NativeTab.CLIPS);
        List<Object> filtered = filter(nativeBar);
        assertNotSame(nativeBar, filtered);
        assertEquals(2, nativeBar.size());
    }

    @Test
    public void unmodifiableInputIsNotTouched() {
        List<Object> fixed = Collections.unmodifiableList(
                list(NativeTab.FEED, NativeTab.CLIPS, NativeTab.DIRECT, NativeTab.PROFILE));
        assertEquals(list(NativeTab.FEED, NativeTab.DIRECT, NativeTab.PROFILE), filter(fixed));
    }

    /**
     * Ties the allowlist to piko's table of Instagram enum names and to the fixed layout: the
     * tabs NAV_CONFIG makes visible are exactly the ones the filter keeps, under every name piko
     * knows for them, and no name of any other tab gets through.
     */
    @Test
    public void allowlistIsExactlyTheVisibleTabsOfTheFixedLayout() throws Exception {
        Method resolve = NavigationBarPatch.class.getDeclaredMethod(
                "resolveConfig", String.class, boolean.class, Set.class);
        resolve.setAccessible(true);
        Config config = (Config) resolve.invoke(null, PostboxNav.NAV_CONFIG, false, Collections.emptySet());
        assertEquals(EnumSet.of(Tab.HOME, Tab.MESSAGES, Tab.PROFILE), config.visible());

        for (Tab tab : Tab.values()) {
            for (String enumName : enumNames(tab)) {
                boolean kept = !PostboxNav.keepAllowedTabs(list(NativeTab.valueOf(enumName))).isEmpty();
                assertEquals(tab + " as " + enumName, config.visible().contains(tab), kept);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> enumNames(Tab tab) throws Exception {
        Field field = Tab.class.getDeclaredField("enumNames");
        field.setAccessible(true);
        return (Set<String>) field.get(tab);
    }
}
