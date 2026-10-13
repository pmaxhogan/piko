package app.crimera.patches.instagram.postbox

import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch

/*
 * Postbox (Vantage fork only, not upstream piko): what a tap inside the app can still reach
 * (L2, D12, D6, D4).
 *
 * The link router filters links that come from outside. Inside the app a hashtag in a caption
 * still opens the search results page, a post's audio label the audio page and a post's location
 * the place page, and on Home the wordmark opens a Following / Favorites menu over a feed that
 * the network block keeps empty.
 *
 * All of it is runtime code in the extension (PostboxSurface), reached through the one
 * ActivityLifecycleCallbacks the core patch installs: each activity's views are looked at after
 * layout, by resource id name. A forbidden page is bounced to the inbox the way the link router
 * bounces a link; the Home title stops taking clicks; the endless loading placeholder under the
 * stories tray is made invisible. No fingerprint, and no Instagram class is edited.
 *
 * This patch only switches the feature on. It works without the link router and the network
 * block, but it is the third layer to those two, not a replacement for either.
 */

private const val POSTBOX_SURFACE_CLASS = "$POSTBOX_EXTENSION/PostboxSurface;"

@Suppress("unused")
val postboxSurfaceLockPatch =
    bytecodePatch(
        name = "Postbox surface lock",
        description = "Postbox builds only. Tapping a hashtag, a post's audio or a post's location inside " +
            "the app lands on Messages instead of the search results, audio or place page. On Home the " +
            "Instagram title no longer opens the Following and Favorites menu, and the loading " +
            "placeholder under the stories tray is hidden.",
        default = false,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(postboxCorePatch)

        execute {
            // Postbox.init calls into it by name; a rename on the Java side must fail here.
            classDefByOrNull(POSTBOX_SURFACE_CLASS)
                ?: throw PatchException("Postbox: extension class $POSTBOX_SURFACE_CLASS not found")
            val postboxBuild = mutableClassDefByOrNull(POSTBOX_BUILD_CLASS)
                ?: throw PatchException("Postbox: extension class $POSTBOX_BUILD_CLASS not found")
            enableBuildFlag(postboxBuild, FLAG_SURFACE_LOCK)
        }
    }
