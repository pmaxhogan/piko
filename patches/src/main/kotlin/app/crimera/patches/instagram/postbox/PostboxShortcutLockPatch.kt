package app.crimera.patches.instagram.postbox

import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getMutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
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
 * - dynamic ones, published by Instagram at runtime. It publishes them when the app goes to the
 *   background, so clearing on launch and resume alone left them on the launcher until the next
 *   time the app was opened. Every call Instagram's code makes to ShortcutManager's
 *   setDynamicShortcuts, addDynamicShortcuts and pushDynamicShortcut is redirected here to the
 *   method of the same name in the extension (PostboxShortcuts), which publishes nothing. The
 *   extension also clears them when the UI goes to the background, when the process starts and
 *   from onActivityResumed, should a publish path have been missed;
 * - pinned ones, which no app can remove: disabled by the extension with a reason string.
 *
 * The redirect needs no fingerprint: the calls name a framework class, which is never
 * obfuscated, so they are found by walking every method of the app. updateShortcuts and
 * requestPinShortcut are left alone: the first only changes shortcuts that already exist, the
 * second pins one, and the extension disables pinned ones.
 */

private const val SHORTCUTS_META_DATA = "android.app.shortcuts"

private const val SHORTCUT_MANAGER = "Landroid/content/pm/ShortcutManager;"
private const val SHORTCUT_INFO = "Landroid/content/pm/ShortcutInfo;"
private const val LIST = "Ljava/util/List;"
private const val EXTENSION_PACKAGE = "Lapp/morphe/extension/"
private const val POSTBOX_SHORTCUTS_CLASS = "$POSTBOX_EXTENSION/PostboxShortcuts;"

/** A ShortcutManager method that publishes dynamic shortcuts: its one parameter and its result. */
private class PublishMethod(val parameter: String, val returnType: String)

private val PUBLISH_METHODS = mapOf(
    "setDynamicShortcuts" to PublishMethod(LIST, "Z"),
    "addDynamicShortcuts" to PublishMethod(LIST, "Z"),
    "pushDynamicShortcut" to PublishMethod(SHORTCUT_INFO, "V"),
)

/** The name of the publish method this instruction calls on a ShortcutManager, or null. */
private fun Instruction.shortcutPublishCall(): String? {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return null
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    val publish = PUBLISH_METHODS[reference.name] ?: return null
    val matches = reference.definingClass == SHORTCUT_MANAGER && reference.returnType == publish.returnType &&
        reference.parameterTypes.map(CharSequence::toString) == listOf(publish.parameter)
    return if (matches) reference.name else null
}

private fun Instruction.callsPostboxShortcuts(name: String): Boolean {
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference
    return (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) &&
        reference?.definingClass == POSTBOX_SHORTCUTS_CLASS && reference.name == name
}

/**
 * L1: every `manager.setDynamicShortcuts(list)`, `addDynamicShortcuts(list)` and
 * `pushDynamicShortcut(info)` in this method becomes a call to the static method of the same
 * name in PostboxShortcuts, with the manager as the first argument. One instruction is replaced
 * by one of the same size on the same registers, so no index moves and a `move-result` that
 * follows still reads the result. Returns the names of the calls redirected.
 */
internal fun MutableMethod.redirectShortcutPublishing(): List<String> {
    val calls = instructions.withIndex().mapNotNull { (index, instruction) ->
        instruction.shortcutPublishCall()?.let { index to it }
    }
    calls.forEach { (index, name) ->
        val call = instructions[index]
        val publish = PUBLISH_METHODS.getValue(name)
        val target = "$POSTBOX_SHORTCUTS_CLASS->$name($SHORTCUT_MANAGER${publish.parameter})${publish.returnType}"
        // The manager and its one argument, in that order, in either form of the call.
        val replacement = when (call) {
            is RegisterRangeInstruction ->
                call.takeIf { it.registerCount == 2 }?.let {
                    "invoke-static/range { v${it.startRegister} .. v${it.startRegister + 1} }, $target"
                }
            is FiveRegisterInstruction ->
                call.takeIf { it.registerCount == 2 }?.let { "invoke-static { v${it.registerC}, v${it.registerD} }, $target" }
            else -> null
        } ?: throw PatchException(
            "Postbox: the call to $SHORTCUT_MANAGER->$name in $definingClass->${this.name} has an unexpected shape " +
                "(${call.opcode.name})",
        )
        replaceInstruction(index, replacement)
    }

    val after = instructions.toList()
    val left = after.count { it.shortcutPublishCall() != null }
    val redirected = calls.count { (index, name) -> after[index].callsPostboxShortcuts(name) }
    if (left != 0 || redirected != calls.size) {
        throw PatchException(
            "Postbox: failed to redirect the shortcut publishing in $definingClass->${this.name} " +
                "($redirected of ${calls.size} redirected, $left left)",
        )
    }
    return calls.map { it.second }
}

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
            // morphe-cli only prints loggers named app.morphe.*, which this context's class is.
            val logger = Logger.getLogger(this::class.java.name)
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
            "are stripped from the manifest, the ones Instagram publishes at runtime are never " +
            "published and are cleared whenever the app is opened or leaves the screen, and shortcuts " +
            "already pinned to the home screen are disabled.",
        default = false,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(postboxCorePatch, postboxShortcutResourcePatch)

        execute {
            // Instagram's own classes will call these three, so the class has to be public too.
            val postboxShortcuts = classDefByOrNull(POSTBOX_SHORTCUTS_CLASS)
                ?: throw PatchException("Postbox: extension class $POSTBOX_SHORTCUTS_CLASS not found")
            if (!AccessFlags.PUBLIC.isSet(postboxShortcuts.accessFlags)) {
                throw PatchException("Postbox: $POSTBOX_SHORTCUTS_CLASS is not public, Instagram's code could not call it")
            }
            PUBLISH_METHODS.forEach { (name, publish) ->
                postboxShortcuts.requirePostboxEntryPoint(
                    name,
                    listOf(SHORTCUT_MANAGER, publish.parameter),
                    publish.returnType,
                )
            }

            // Resolve every target before editing any, so a failure leaves nothing half done.
            val postboxBuild = mutableClassDefByOrNull(POSTBOX_BUILD_CLASS)
                ?: throw PatchException("Postbox: extension class $POSTBOX_BUILD_CLASS not found")
            postboxBuild.requireBuildFlag(FLAG_SHORTCUT_LOCK)

            // Collected in one pass and edited after, so classes are not rewritten while read.
            // The extension is skipped: PostboxShortcuts makes these very calls itself.
            val publishers = mutableListOf<Method>()
            classDefForEach { classDef ->
                if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
                classDef.methods.forEach { method ->
                    if (method.implementation?.instructions?.any { it.shortcutPublishCall() != null } == true) {
                        publishers += method
                    }
                }
            }
            if (publishers.isEmpty()) {
                // 447.0.0.55.81 has five such calls in four methods. None at all means Instagram
                // publishes some other way now, and the first layer of the lock would be gone.
                throw PatchException(
                    "Postbox: no call to $SHORTCUT_MANAGER->${PUBLISH_METHODS.keys.joinToString("/")} found in the app. " +
                        "Find how this version publishes its dynamic shortcuts before this can ship",
                )
            }

            val redirected = publishers.map { method ->
                "${method.definingClass}->${method.name}" to method.getMutableMethod().redirectShortcutPublishing()
            }
            Logger.getLogger(this::class.java.name).info(
                "Postbox shortcut lock: redirected ${redirected.sumOf { it.second.size }} shortcut publish call(s) " +
                    "in ${redirected.size} method(s): " +
                    redirected.joinToString("; ") { (method, calls) -> "$method ${calls.joinToString()}" },
            )

            enableBuildFlag(postboxBuild, FLAG_SHORTCUT_LOCK)
        }
    }
