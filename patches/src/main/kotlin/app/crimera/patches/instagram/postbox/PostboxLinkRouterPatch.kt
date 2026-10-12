package app.crimera.patches.instagram.postbox

import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch

/*
 * Postbox (Vantage fork only, not upstream piko): deep links and startup intents (L2, L3, D12).
 *
 * All of it is runtime code in the extension (PostboxLinks decides, PostboxLinkRouter acts),
 * reached through the one ActivityLifecycleCallbacks the core patch installs: every activity of
 * the app is inspected before its own onCreate reads its intent. That covers links opened from
 * other apps, notification taps, shortcuts and adb alike, without a fingerprint for any of
 * Instagram's link-handling activities.
 *
 * This patch only switches the feature on. It does not depend on the settings lock: the router
 * reuses PostboxNav's startup-tab allowlist, which is plain extension code.
 */

@Suppress("unused")
val postboxLinkRouterPatch =
    bytecodePatch(
        name = "Postbox link router",
        description = "Postbox builds only. Links and intents that would open Home, Explore, the Reels " +
            "tab, search, hashtag or location pages land on Messages instead. Links to a single post, " +
            "reel, story, profile or chat, and the login and account pages, open as usual.",
        default = false,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(postboxCorePatch)

        execute {
            val postboxBuild = mutableClassDefByOrNull(POSTBOX_BUILD_CLASS)
                ?: throw PatchException("Postbox: extension class $POSTBOX_BUILD_CLASS not found")
            enableBuildFlag(postboxBuild, FLAG_LINK_ROUTER)
        }
    }
