package app.crimera.patches.instagram.postbox

import app.crimera.patches.instagram.ads.hideSuggestedContentPatch
import app.crimera.patches.instagram.links.interceptUriPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.LINKS_DESCRIPTOR
import app.crimera.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.p0Register
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue
import com.android.tools.smali.dexlib2.iface.value.TypeEncodedValue

/*
 * Postbox (Vantage fork only, not upstream piko): the network layer of the lock-down (D4, D7).
 *
 * The other Postbox patches close the ways INTO the feed, Explore and the Reels tab. This one
 * makes those surfaces empty if a way in was missed, and it is the whole implementation of
 * "Home is the stories tray over an empty feed" and "nothing pages in below an opened post".
 *
 * Both hooks are prepended to piko's own extension methods, found by class and method name:
 *
 * - Links.interceptUri(URI), piko's single choke point for Tigon requests (interceptUriPatch
 *   puts the call into TigonServiceLayer.startRequest). PostboxNet.interceptUri runs first and
 *   throws IOException for a blocked path. The call has to sit OUTSIDE piko's try block: that
 *   block catches Exception, so a throw from inside it would be swallowed and the block would
 *   silently do nothing. The patch checks that and fails the build otherwise.
 * - Block.replaceJsonParserKey(String), piko's hook on the feed-item parser
 *   (hideSuggestedContentPatch puts the call there). PostboxNet.replaceJsonParserKey rewrites
 *   the key first, so two more unit types get piko's "unknown key" token and are dropped.
 */

private const val STRING = "Ljava/lang/String;"
private const val URI = "Ljava/net/URI;"
private const val IO_EXCEPTION = "Ljava/io/IOException;"
private const val THROWS_ANNOTATION = "Ldalvik/annotation/Throws;"

private const val POSTBOX_NET_CLASS = "$POSTBOX_EXTENSION/PostboxNet;"
private const val BLOCK_CLASS = "$PATCHES_DESCRIPTOR/Block;"
private const val INTERCEPT_URI = "interceptUri"
private const val REPLACE_JSON_PARSER_KEY = "replaceJsonParserKey"

/** Whether the method's `throws` clause (kept in the dex as a system annotation) names this type. */
private fun Method.declaresThrown(type: String) =
    annotations.any { annotation ->
        annotation.type == THROWS_ANNOTATION && annotation.elements.any { element ->
            (element.value as? ArrayEncodedValue)?.value?.any { (it as? TypeEncodedValue)?.value == type } == true
        }
    }

private fun Instruction.callsPostboxNet(name: String): Boolean {
    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference
    return opcode == Opcode.INVOKE_STATIC_RANGE && reference?.definingClass == POSTBOX_NET_CLASS && reference.name == name
}

/**
 * Whether a try block covers the first instruction of the method. `tries` in a dex are address
 * ranges; code prepended at index 0 lands at address 0, in front of whatever the range starts at.
 */
private fun MutableMethod.firstInstructionIsInsideTry() =
    implementation!!.tryBlocks.any { it.startCodeAddress <= 0 && it.startCodeAddress + it.codeUnitCount > 0 }

/**
 * D4, D7: `PostboxNet.interceptUri(uri)` becomes the first instruction of piko's
 * `Links.interceptUri(URI)`.
 */
internal fun hookInterceptUri(links: MutableClass) {
    val interceptUri = links.requireExtensionMethod(INTERCEPT_URI, listOf(URI), "V")
    // The block works by throwing IOException out of this method. That is only safe while the
    // method is declared to throw it, i.e. while its caller is written to expect it.
    if (!interceptUri.declaresThrown(IO_EXCEPTION)) {
        throw PatchException(
            "Postbox: ${links.type}->$INTERCEPT_URI no longer declares IOException. " +
                "Upstream changed how a request is blocked; the network block must be re-pointed",
        )
    }

    // Static method: p0 is the URI. The range form takes any register number.
    val uriRegister = interceptUri.p0Register
    interceptUri.addInstruction(
        0,
        "invoke-static/range { v$uriRegister .. v$uriRegister }, $POSTBOX_NET_CLASS->$INTERCEPT_URI($URI)V",
    )

    if (!interceptUri.instructions.first().callsPostboxNet(INTERCEPT_URI)) {
        throw PatchException("Postbox: failed to prepend the network block to ${links.type}->$INTERCEPT_URI")
    }
    if (interceptUri.firstInstructionIsInsideTry()) {
        throw PatchException(
            "Postbox: the network block landed inside a try block of ${links.type}->$INTERCEPT_URI. " +
                "Its IOException would be caught there and nothing would be blocked",
        )
    }
}

/**
 * D7: `key = PostboxNet.replaceJsonParserKey(key)` becomes the first thing piko's
 * `Block.replaceJsonParserKey(String)` does. The result is written back into the parameter
 * register, so piko's own logic runs unchanged on it: its "unknown key" token is not in piko's
 * set, so it is returned as is, and every other key is handled exactly as before.
 */
internal fun hookJsonParserKey(block: MutableClass) {
    val replaceKey = block.requireExtensionMethod(REPLACE_JSON_PARSER_KEY, listOf(STRING), STRING)

    // Static method: p0 is the key.
    val keyRegister = replaceKey.p0Register
    replaceKey.addInstructions(
        0,
        """
            invoke-static/range { v$keyRegister .. v$keyRegister }, $POSTBOX_NET_CLASS->$REPLACE_JSON_PARSER_KEY($STRING)$STRING
            move-result-object v$keyRegister
        """.trimIndent(),
    )

    val head = replaceKey.instructions.take(2)
    if (!head[0].callsPostboxNet(REPLACE_JSON_PARSER_KEY) || head[1].opcode != Opcode.MOVE_RESULT_OBJECT) {
        throw PatchException("Postbox: failed to prepend the parser filter to ${block.type}->$REPLACE_JSON_PARSER_KEY")
    }
}

@Suppress("unused")
val postboxNetworkBlockPatch =
    bytecodePatch(
        name = "Postbox network block",
        description = "Postbox builds only. Blocks the requests behind the home feed, Explore, the Reels " +
            "tab and the posts and reels suggested after the one you opened, so those surfaces stay " +
            "empty however they are reached. The stories tray, messages, profiles and single posts are " +
            "not affected.",
        default = false,
    ) {
        val traceNetwork by booleanOption(
            key = "traceNetwork",
            default = false,
            title = "Trace network requests",
            description = "Logs the path of every request to logcat (tag PostboxNet; blocked ones also " +
                "under PostboxNetBlock) to find the endpoints a screen uses. For verification builds " +
                "ONLY - never enable in a release.",
        )

        compatibleWith(COMPATIBILITY_INSTAGRAM)

        // interceptUriPatch puts the request choke point in place and hideSuggestedContentPatch
        // the parser hook; without them the two extension methods below are never called.
        dependsOn(interceptUriPatch, hideSuggestedContentPatch)

        execute {
            fun mutableExtensionClass(type: String) =
                mutableClassDefByOrNull(type)
                    ?: throw PatchException("Postbox: extension class $type not found")

            val postboxNet = classDefByOrNull(POSTBOX_NET_CLASS)
                ?: throw PatchException("Postbox: extension class $POSTBOX_NET_CLASS not found")
            postboxNet.requirePostboxEntryPoint(INTERCEPT_URI, listOf(URI), "V")
            postboxNet.requirePostboxEntryPoint(REPLACE_JSON_PARSER_KEY, listOf(STRING), STRING)

            // Resolve every target before editing any, so a failure leaves nothing half done.
            val postboxBuild = mutableExtensionClass(POSTBOX_BUILD_CLASS)
            val links = mutableExtensionClass(LINKS_DESCRIPTOR)
            val block = mutableExtensionClass(BLOCK_CLASS)
            links.requireExtensionMethod(INTERCEPT_URI, listOf(URI), "V")
            block.requireExtensionMethod(REPLACE_JSON_PARSER_KEY, listOf(STRING), STRING)
            postboxBuild.requireBuildFlag(FLAG_NETWORK_BLOCK)
            postboxBuild.requireBuildFlag(FLAG_TRACE_NETWORK)

            hookInterceptUri(links)
            hookJsonParserKey(block)
            enableBuildFlag(postboxBuild, FLAG_NETWORK_BLOCK)
            if (traceNetwork == true) enableBuildFlag(postboxBuild, FLAG_TRACE_NETWORK)
        }
    }
