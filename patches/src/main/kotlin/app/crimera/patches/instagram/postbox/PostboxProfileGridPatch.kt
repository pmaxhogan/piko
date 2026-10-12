package app.crimera.patches.instagram.postbox

import app.crimera.patches.instagram.entity.userdata.userDataEntity
import app.crimera.patches.instagram.misc.actionBar.userProfileActionBarButton.userProfileActionBarButtonPatch
import app.crimera.patches.instagram.utils.Constants.ACTIONBAR_DESCRIPTOR
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.util.p0Register
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Postbox (Vantage fork only, not upstream piko): no posts or reels grid on other people's
 * profiles (D8). The user's own profile keeps its grid.
 *
 * Two layers, both runtime code in the extension:
 *
 * - View: PostboxProfile.onProfileBound becomes the first instruction of piko's own extension
 *   method ActionBarPatch.userProfileActionBarButton(Activity, ViewGroup, UserSession, Object).
 *   Piko's userProfileActionBarButtonPatch calls that from Instagram's profile action bar
 *   builder with the live session and the user the profile shows, so it says who is looking and
 *   whose profile it is. PostboxProfile hides the tab strip and the pager under the header by
 *   view id name, inside that profile's own view tree only.
 * - Network: the same call teaches PostboxProfileNet the logged-in user id, and PostboxNet's
 *   existing hook in Links.interceptUri (owned by "Postbox network block") then fails the
 *   requests that fill somebody else's grid. No second prepend in Links.interceptUri.
 *
 * Like the other Postbox patches this edits piko's extension dex by class and method name and
 * has no fingerprint of its own. The hook cannot throw (PostboxProfile catches everything), so
 * unlike the network block it does not matter that it lands inside piko's try block.
 *
 * NOT verified on a device: the view id names and the request paths are taken from public
 * dumps and the long-standing private API, not from the APK. See PostboxProfile and
 * PostboxProfileNet.
 */

private const val ACTIVITY = "Landroid/app/Activity;"
private const val VIEW_GROUP = "Landroid/view/ViewGroup;"
private const val OBJECT = "Ljava/lang/Object;"

private const val POSTBOX_PROFILE_CLASS = "$POSTBOX_EXTENSION/PostboxProfile;"
private const val USER_PROFILE_ACTION_BAR_BUTTON = "userProfileActionBarButton"
private const val ON_PROFILE_BOUND = "onProfileBound"

/** The signature piko's seam has today, and the one the Postbox hook mirrors. */
private val PROFILE_BIND_PARAMETERS = listOf(ACTIVITY, VIEW_GROUP, USER_SESSION_CLASS, OBJECT)

/**
 * D8: `PostboxProfile.onProfileBound(activity, actionBar, session, user)` becomes the first
 * instruction of piko's `ActionBarPatch.userProfileActionBarButton`, with the method's own four
 * arguments.
 */
internal fun hookProfileBind(actionBar: MutableClass) {
    val seam = actionBar.requireExtensionMethod(USER_PROFILE_ACTION_BAR_BUTTON, PROFILE_BIND_PARAMETERS, "V")

    // Static method, four object parameters: they are the last four registers, in order, so one
    // range covers them whatever the method's register count is.
    val first = seam.p0Register
    val last = first + PROFILE_BIND_PARAMETERS.size - 1
    if (last != seam.implementation!!.registerCount - 1) {
        throw PatchException(
            "Postbox: the parameters of ${actionBar.type}->$USER_PROFILE_ACTION_BAR_BUTTON are not its last " +
                "${PROFILE_BIND_PARAMETERS.size} registers (v$first..v$last of ${seam.implementation!!.registerCount})",
        )
    }
    seam.addInstruction(
        0,
        "invoke-static/range { v$first .. v$last }, " +
            "$POSTBOX_PROFILE_CLASS->$ON_PROFILE_BOUND(${PROFILE_BIND_PARAMETERS.joinToString("")})V",
    )

    val added = seam.instructions.first()
    val reference = (added as? ReferenceInstruction)?.reference as? MethodReference
    val range = added as? RegisterRangeInstruction
    if (added.opcode != Opcode.INVOKE_STATIC_RANGE || reference?.definingClass != POSTBOX_PROFILE_CLASS ||
        reference.name != ON_PROFILE_BOUND || range?.startRegister != first ||
        range.registerCount != PROFILE_BIND_PARAMETERS.size
    ) {
        throw PatchException(
            "Postbox: failed to prepend the profile hook to ${actionBar.type}->$USER_PROFILE_ACTION_BAR_BUTTON",
        )
    }
}

@Suppress("unused")
val postboxProfileGridPatch =
    bytecodePatch(
        name = "Postbox profile grid",
        description = "Postbox builds only. Other people's profiles show the header, bio, highlights and " +
            "follower lists but no posts or reels grid: the grid is hidden and the requests that fill it " +
            "are blocked. Your own profile keeps its grid.",
        default = false,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        // - postboxCorePatch: Postbox.init, whose lifecycle callbacks notice an account switch.
        // - postboxNetworkBlockPatch: owns the Links.interceptUri hook the network layer runs in.
        // - userProfileActionBarButtonPatch: puts the call to the seam into Instagram's profile
        //   action bar builder. Without it the method below is never called.
        // - userDataEntity: fills in the user id getter name that piko's UserData.getUserId uses.
        dependsOn(postboxCorePatch, postboxNetworkBlockPatch, userProfileActionBarButtonPatch, userDataEntity)

        execute {
            fun mutableExtensionClass(type: String) =
                mutableClassDefByOrNull(type)
                    ?: throw PatchException("Postbox: extension class $type not found")

            val postboxProfile = classDefByOrNull(POSTBOX_PROFILE_CLASS)
                ?: throw PatchException("Postbox: extension class $POSTBOX_PROFILE_CLASS not found")
            postboxProfile.requirePostboxEntryPoint(ON_PROFILE_BOUND, PROFILE_BIND_PARAMETERS, "V")

            // Resolve every target before editing any, so a failure leaves nothing half done.
            val postboxBuild = mutableExtensionClass(POSTBOX_BUILD_CLASS)
            val actionBar = mutableExtensionClass(ACTIONBAR_DESCRIPTOR)
            actionBar.requireExtensionMethod(USER_PROFILE_ACTION_BAR_BUTTON, PROFILE_BIND_PARAMETERS, "V")
            postboxBuild.requireBuildFlag(FLAG_PROFILE_GRID)

            hookProfileBind(actionBar)
            enableBuildFlag(postboxBuild, FLAG_PROFILE_GRID)
        }
    }
