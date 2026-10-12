package app.crimera.patches.instagram.postbox

import app.crimera.patches.instagram.misc.extension.hooks.instagramInitHook
import app.crimera.patches.instagram.misc.extension.sharedExtensionPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * Postbox (Vantage fork only, not upstream piko): what the Postbox patches share.
 *
 * - The names of the Postbox extension classes.
 * - The build switches: every Postbox feature is off until its patch rewrites one getter of the
 *   extension class PostboxBuild from `return false` to `return true` (the X "Daily time limit"
 *   BuildValues idea). Nothing at runtime can change one.
 * - The internal core patch, which calls Postbox.init(Context) from Instagram's
 *   Application.onCreate through piko's own init seam. The features that live on activity
 *   lifecycle events depend on it.
 *
 * Like the settings lock, this only ever edits piko's extension dex by class and method name,
 * plus the one Application.onCreate that piko's shared extension patch already hooks. It adds
 * no fingerprint for obfuscated Instagram code, and everything that could have moved fails the
 * build with a PatchException naming it.
 */

internal const val POSTBOX_EXTENSION = "$PATCHES_DESCRIPTOR/postbox"
internal const val POSTBOX_HUB_CLASS = "$POSTBOX_EXTENSION/Postbox;"
internal const val POSTBOX_BUILD_CLASS = "$POSTBOX_EXTENSION/PostboxBuild;"

internal const val POSTBOX_INIT = "init"
private const val CONTEXT = "Landroid/content/Context;"

/** The PostboxBuild getters, by the patch that owns each. */
internal const val FLAG_SHORTCUT_LOCK = "shortcutLock"
internal const val FLAG_LINK_ROUTER = "linkRouter"
internal const val FLAG_NETWORK_BLOCK = "networkBlock"
internal const val FLAG_PROFILE_GRID = "profileGrid"
internal const val FLAG_TRACE_NETWORK = "traceNetwork"

internal fun Method.postboxParameterTypes() = parameterTypes.map(CharSequence::toString)

/**
 * Requires a public static method with this exact signature on an extension class. The patches
 * reference these only by name in smali, so a rename on the Java side would otherwise surface
 * as a NoSuchMethodError on the device instead of a failed build.
 */
internal fun ClassDef.requirePostboxEntryPoint(name: String, parameters: List<String>, returnType: String) {
    val found = methods.any {
        it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
            it.postboxParameterTypes() == parameters && it.returnType == returnType
    }
    if (!found) {
        throw PatchException(
            "Postbox: $type has no public static $name(${parameters.joinToString("")})$returnType",
        )
    }
}

/**
 * The one static method of an extension class with this name and signature, with a body.
 * Used for the piko methods the Postbox patches prepend to.
 */
internal fun MutableClass.requireExtensionMethod(
    name: String,
    parameters: List<String>,
    returnType: String,
): MutableMethod {
    val candidates = methods.filter { it.name == name }
    val method = candidates.singleOrNull()
        ?: throw PatchException(
            "Postbox: expected exactly one $type->$name in the extension, found ${candidates.size}. " +
                "Upstream renamed, removed or overloaded it; the hook must be re-pointed before this can ship",
        )
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.postboxParameterTypes() != parameters ||
        method.returnType != returnType || method.implementation == null
    ) {
        throw PatchException(
            "Postbox: $type->$name has an unexpected signature " +
                "(${method.postboxParameterTypes().joinToString("")})${method.returnType}, " +
                "expected static (${parameters.joinToString("")})$returnType",
        )
    }
    return method
}

/**
 * The PostboxBuild getter with this name, checked to be exactly what the Java source compiles
 * to: `const/4 vX, 0x0` + `return vX`. Anything else (a renamed getter, a getter that grew
 * logic, a switch that something already turned on) fails the build.
 */
internal fun MutableClass.requireBuildFlag(name: String): MutableMethod {
    val getter = methods.singleOrNull { it.name == name }
        ?: throw PatchException("Postbox: $type has no single $name() getter")
    if (!AccessFlags.STATIC.isSet(getter.accessFlags) || getter.parameterTypes.isNotEmpty() || getter.returnType != "Z") {
        throw PatchException("Postbox: $type->$name is not a static boolean getter without parameters")
    }
    val body = getter.implementation?.instructions?.toList()
        ?: throw PatchException("Postbox: $type->$name() has no implementation")
    val constant = body.getOrNull(0)
    val result = body.getOrNull(1)
    val isFalseConstant = body.size == 2 &&
        constant?.opcode == Opcode.CONST_4 && (constant as NarrowLiteralInstruction).narrowLiteral == 0 &&
        result?.opcode == Opcode.RETURN &&
        (result as OneRegisterInstruction).registerA == (constant as OneRegisterInstruction).registerA
    if (!isFalseConstant) {
        val alreadyOn = body.size == 2 && constant?.opcode == Opcode.CONST_4 &&
            (constant as NarrowLiteralInstruction).narrowLiteral == 1
        throw PatchException(
            if (alreadyOn) {
                "Postbox: $type->$name() is already on. Each switch belongs to exactly one patch and is set once"
            } else {
                "Postbox: $type->$name() is not a plain `return false` " +
                    "(${body.joinToString { it.opcode.name }}). It must stay a bare constant getter that is off by default"
            },
        )
    }
    return getter
}

/**
 * Turns one Postbox feature on for this build: the getter's `const/4 vX, 0x0` becomes
 * `const/4 vX, 0x1`. One instruction is replaced by one of the same size in the same method
 * object, so there is nothing to keep consistent.
 */
internal fun enableBuildFlag(postboxBuild: MutableClass, name: String) {
    val getter = postboxBuild.requireBuildFlag(name)
    val register = (getter.instructions.first() as OneRegisterInstruction).registerA
    getter.replaceInstruction(0, "const/4 v$register, 0x1")

    val head = getter.instructions.toList()
    val constant = head.getOrNull(0)
    if (head.size != 2 || constant?.opcode != Opcode.CONST_4 ||
        (constant as NarrowLiteralInstruction).narrowLiteral != 1 || head[1].opcode != Opcode.RETURN
    ) {
        throw PatchException("Postbox: failed to turn on ${postboxBuild.type}->$name()")
    }
}

/**
 * Adds the Postbox.init(Context) call to Instagram's Application.onCreate, right after
 * super.onCreate(): the same method, the same position and the same register piko's own init
 * hook and settings patch use (the object `super.onCreate()` is invoked on, i.e. the
 * Application). Postbox.init takes a Context, which an Application always is, so the call
 * verifies whatever Instagram's Application class extends.
 */
internal fun hookApplicationInit(onCreate: MutableMethod) {
    val superCall = onCreate.instructions.withIndex().firstOrNull { (_, instruction) ->
        instruction.opcode == Opcode.INVOKE_SUPER || instruction.opcode == Opcode.INVOKE_SUPER_RANGE
    } ?: throw PatchException(
        "Postbox: ${onCreate.definingClass}->${onCreate.name} has no super call to hook the init after",
    )
    val applicationRegister = superCall.value.registersUsed.firstOrNull()
        ?: throw PatchException("Postbox: the super call in ${onCreate.definingClass}->${onCreate.name} uses no register")

    // The range form takes any register number.
    onCreate.addInstruction(
        superCall.index + 1,
        "invoke-static/range { v$applicationRegister .. v$applicationRegister }, " +
            "$POSTBOX_HUB_CLASS->$POSTBOX_INIT($CONTEXT)V",
    )

    val added = onCreate.instructions[superCall.index + 1]
    val reference = (added as? ReferenceInstruction)?.reference as? MethodReference
    if (added.opcode != Opcode.INVOKE_STATIC_RANGE || reference?.definingClass != POSTBOX_HUB_CLASS ||
        reference.name != POSTBOX_INIT
    ) {
        throw PatchException("Postbox: failed to add the init call to ${onCreate.definingClass}->${onCreate.name}")
    }
}

/**
 * Internal (unnamed) patch the lifecycle features depend on. On its own it changes nothing a
 * user could notice: Postbox.init returns straight away while every PostboxBuild switch is off.
 */
internal val postboxCorePatch =
    bytecodePatch(
        description = "Postbox builds only. Starts the Postbox runtime from Instagram's Application.onCreate.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(sharedExtensionPatch)

        execute {
            val hub = classDefByOrNull(POSTBOX_HUB_CLASS)
                ?: throw PatchException("Postbox: extension class $POSTBOX_HUB_CLASS not found")
            hub.requirePostboxEntryPoint(POSTBOX_INIT, listOf(CONTEXT), "V")

            hookApplicationInit(instagramInitHook.fingerprint.method)
        }
    }
