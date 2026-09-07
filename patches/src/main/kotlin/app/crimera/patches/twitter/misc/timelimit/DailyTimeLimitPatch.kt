package app.crimera.patches.twitter.misc.timelimit

import app.crimera.patches.twitter.misc.extension.sharedExtensionPatch
import app.crimera.patches.twitter.misc.settings.SettingsPreferenceFingerprint
import app.crimera.patches.twitter.misc.settings.settingsPatch
import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

private const val TL_PACKAGE = "$PATCHES_DESCRIPTOR/timelimit"
private const val TL_CLASS = "$TL_PACKAGE/TimeLimit;"
private const val BUILD_VALUES_TYPE = "Lapp/morphe/extension/twitter/patches/timelimit/BuildValues;"
private const val JAVA_PACKAGE = "app.morphe.extension.twitter.patches.timelimit"

/** The hard ceiling; a build cannot be configured above it whatever the option says. */
private const val ABSOLUTE_MAX_MINUTES = 120

private object ApplicationOnCreateFingerprint : Fingerprint(
    name = "onCreate",
    returnType = "V",
    parameters = listOf(),
    custom = { _, classDef ->
        classDef.superclass?.contains("Landroid/app/Application;") == true
    },
)

/**
 * Manifest + settings-list side: the two special-access permissions, the
 * three Vantage screens, and a "Daily time limit" row in X's own settings list
 * right above Piko's (same mechanism Piko uses, so it breaks only if Piko's does).
 */
internal val dailyTimeLimitResourcePatch =
    resourcePatch {
        execute {
            document("AndroidManifest.xml").use { doc ->
                val manifest = doc.documentElement
                val application = doc.getElementsByTagName("application").item(0) as Element?
                    ?: throw PatchException("no <application> in AndroidManifest")

                listOf(
                    "android.permission.PACKAGE_USAGE_STATS",
                    "android.permission.MANAGE_EXTERNAL_STORAGE",
                ).forEach { perm ->
                    val el = doc.createElement("uses-permission")
                    el.setAttribute("android:name", perm)
                    manifest.insertBefore(el, application)
                }

                listOf("LockActivity", "LimitConfigActivity", "SetupActivity").forEach { cls ->
                    val activity = doc.createElement("activity")
                    activity.setAttribute("android:name", "$JAVA_PACKAGE.$cls")
                    activity.setAttribute("android:exported", "false")
                    activity.setAttribute("android:theme", "@android:style/Theme.Material.NoActionBar")
                    activity.setAttribute("android:configChanges", "orientation|screenSize|keyboardHidden|uiMode")
                    activity.setAttribute("android:excludeFromRecents", "false")
                    application.appendChild(activity)
                }
            }

            document("res/xml/settings_root.xml").use { editor ->
                val parent = editor.getElementsByTagName("PreferenceScreen").item(0) as Element
                val pref = editor.createElement("Preference")
                pref.setAttribute("android:icon", "@drawable/ic_vector_clock")
                pref.setAttribute("android:title", "Daily time limit")
                pref.setAttribute("android:key", "pref_vantage_time_limit")
                pref.setAttribute("android:order", "109")
                // An explicit intent child: the preference framework starts it on
                // click regardless of how X dispatches its own rows, so the row
                // works even if the bytecode click hook below is never reached.
                val intent = editor.createElement("intent")
                intent.setAttribute("android:action", "android.intent.action.VIEW")
                intent.setAttribute("android:targetPackage", "com.twitter.android")
                intent.setAttribute("android:targetClass", "$JAVA_PACKAGE.LimitConfigActivity")
                pref.appendChild(intent)
                parent.appendChild(pref)
            }
        }
    }

@Suppress("unused")
val dailyTimeLimitPatch =
    bytecodePatch(
        name = "Daily time limit",
        description = "Mandatory daily time limit (default 30 minutes, reset at 5:00 AM) that locks X " +
            "when it is used up. Tracked by the system's usage statistics plus redundant copies outside " +
            "the app, so force-close, reboot, clearing data or reinstalling do not reset it.",
    ) {
        val maxLimitMinutes by intOption(
            key = "maxLimitMinutes",
            default = ABSOLUTE_MAX_MINUTES,
            title = "Maximum daily limit (minutes)",
            description = "The highest daily limit a user can configure in this build (1-120). " +
                "The default limit is 30 minutes or this value, whichever is lower.",
            required = true,
            validator = { it != null && it in 1..ABSOLUTE_MAX_MINUTES },
        )
        val enableTestHooks by booleanOption(
            key = "enableTestHooks",
            default = false,
            title = "Enable test hooks",
            description = "Honour the adb debug clock offset (debug.vantagex.offset_ms). " +
                "For verification builds ONLY - never enable in a release.",
        )

        compatibleWith(COMPATIBILITY_X)
        dependsOn(sharedExtensionPatch, settingsPatch, dailyTimeLimitResourcePatch)

        execute {
            val max = maxLimitMinutes ?: ABSOLUTE_MAX_MINUTES
            if (max !in 1..ABSOLUTE_MAX_MINUTES) throw PatchException("maxLimitMinutes must be 1..$ABSOLUTE_MAX_MINUTES, got $max")

            // 1. Start the controller from Application.onCreate, right after super.onCreate.
            ApplicationOnCreateFingerprint.method.apply {
                val superIndex = indexOfFirstInstruction {
                    opcode == Opcode.INVOKE_SUPER && getReference<MethodReference>()?.name == "onCreate"
                }
                addInstruction(
                    if (superIndex >= 0) superIndex + 1 else 0,
                    "invoke-static { p0 }, $TL_CLASS->init(Landroid/app/Application;)V",
                )
            }

            // 2. Route the settings-list row to the config screen (same site Piko hooks for its row).
            SettingsPreferenceFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches.first().index
                    val register = getInstruction<TwoRegisterInstruction>(index).registerA
                    val freeRegister = findFreeRegister(index + 1, register)
                    addInstructionsWithLabels(
                        index + 1,
                        """
                            invoke-static { v$register }, $TL_CLASS->onSettingsPrefClick(Ljava/lang/String;)Z
                            move-result v$freeRegister
                            if-eqz v$freeRegister, :vx_not_ours
                            const/4 v$freeRegister, 0x1
                            return v$freeRegister
                            :vx_not_ours
                            nop
                        """,
                    )
                }
            }

            // 3. Bake the build variant into BuildValues: each getter is `const vN; return vN`.
            val buildValues = mutableClassDefBy(BUILD_VALUES_TYPE)
            fun rewrite(methodName: String, smali: String) {
                val m = buildValues.methods.firstOrNull { it.name == methodName }
                    ?: throw PatchException("BuildValues.$methodName not found in the extension")
                val count = m.implementation?.instructions?.count() ?: 0
                if (count != 2) throw PatchException("BuildValues.$methodName has $count instructions, expected 2 (const + return)")
                m.replaceInstruction(0, smali)
            }
            rewrite("maxLimitMinutes", "const/16 v0, $max")
            rewrite("testHooksEnabled", "const/4 v0, ${if (enableTestHooks == true) 1 else 0}")
        }
    }
