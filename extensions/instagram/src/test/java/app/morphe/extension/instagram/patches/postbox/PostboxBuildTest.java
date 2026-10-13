package app.morphe.extension.instagram.patches.postbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

/**
 * The build switches as they are in source: all off. A Postbox feature may only ever be turned
 * on by its patch rewriting the getter in the extension dex, never by a default.
 */
public class PostboxBuildTest {
    /** One per Postbox patch (plus the trace option); the patches look these up by name. */
    private static final Set<String> EXPECTED = new TreeSet<>(
            Arrays.asList("shortcutLock", "linkRouter", "networkBlock", "profileGrid", "surfaceLock",
                    "traceNetwork"));

    @Test
    public void everySwitchIsOffByDefault() throws Exception {
        Set<String> found = new TreeSet<>();
        for (Method method : PostboxBuild.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            int modifiers = method.getModifiers();
            assertTrue(method.getName() + " must be public static",
                    Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers));
            assertEquals(method.getName() + " must return boolean", boolean.class, method.getReturnType());
            assertEquals(method.getName() + " must take no parameters", 0, method.getParameterCount());
            assertFalse(method.getName() + " must default to false", (Boolean) method.invoke(null));
            found.add(method.getName());
        }
        assertEquals(EXPECTED, found);
    }

    @Test
    public void directCallsAgree() {
        assertFalse(PostboxBuild.shortcutLock());
        assertFalse(PostboxBuild.linkRouter());
        assertFalse(PostboxBuild.networkBlock());
        assertFalse(PostboxBuild.profileGrid());
        assertFalse(PostboxBuild.surfaceLock());
        assertFalse(PostboxBuild.traceNetwork());
    }
}
