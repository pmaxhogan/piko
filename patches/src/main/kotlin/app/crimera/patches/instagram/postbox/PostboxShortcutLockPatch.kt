package app.crimera.patches.instagram.postbox

import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.util.logging.Logger

/*
 * Postbox (Vantage fork only, not upstream piko): no launcher shortcuts (L1).
 *
 * A launcher long-press must not offer a way into the camera, a new post, Reels or anything
 * else. Three kinds of shortcut exist and each needs its own answer:
 *
 * - static ones, declared in the manifest: removed here, at patch time;
 * - dynamic ones, published by Instagram at runtime: cleared by the extension (PostboxShortcuts)
 *   when the process starts and from onActivityResumed, at most once a minute;
 * - pinned ones, which no app can remove: disabled by the extension with a reason string.
 */

private const val SHORTCUTS_META_DATA = "android.app.shortcuts"

/**
 * Removes every `<meta-data android:name="android.app.shortcuts" .../>` from the manifest,
 * whichever `<activity>` or `<activity-alias>` carries it, and returns how many there were.
 * The shortcuts XML the entries point at is left in place; unreferenced, it does nothing.
 */
internal fun removeShortcutMetaData(manifest: Document): Int {
    // getElementsByTagName returns a live list: collect first, then remove.
    val nodes = manifest.getElementsByTagName("meta-data")
    val shortcuts = (0 until nodes.length)
        .mapNotNull { nodes.item(it) as? Element }
        .filter { it.getAttribute("android:name") == SHORTCUTS_META_DATA }
    shortcuts.forEach { it.parentNode?.removeChild(it) }
    return shortcuts.size
}

private val postboxShortcutResourcePatch =
    resourcePatch {
        execute {
            val removed = document("AndroidManifest.xml").use { removeShortcutMetaData(it) }
            val logger = Logger.getLogger("PostboxShortcutLock")
            if (removed == 0) {
                // Not a failure: the runtime half still clears whatever gets published. But it
                // means this build's manifest declares its shortcuts somewhere else, or not at all.
                logger.warning(
                    "Postbox shortcut lock: no <meta-data android:name=\"$SHORTCUTS_META_DATA\"> in the manifest, " +
                        "nothing to strip. Check `dumpsys shortcut` on the built app for static shortcuts",
                )
            } else {
                logger.info("Postbox shortcut lock: removed $removed static shortcut declaration(s) from the manifest")
            }
        }
    }

@Suppress("unused")
val postboxShortcutLockPatch =
    bytecodePatch(
        name = "Postbox shortcut lock",
        description = "Postbox builds only. Removes the launcher long-press shortcuts: the static ones " +
            "are stripped from the manifest, the ones Instagram publishes at runtime are cleared on " +
            "every launch and re-checked once a minute, and shortcuts already pinned to the home " +
            "screen are disabled.",
        default = false,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(postboxCorePatch, postboxShortcutResourcePatch)

        execute {
            val postboxBuild = mutableClassDefByOrNull(POSTBOX_BUILD_CLASS)
                ?: throw PatchException("Postbox: extension class $POSTBOX_BUILD_CLASS not found")
            enableBuildFlag(postboxBuild, FLAG_SHORTCUT_LOCK)
        }
    }
