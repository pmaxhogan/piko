package app.crimera.patches.instagram.postbox

import app.crimera.patches.instagram.ads.hideSuggestedContentPatch
import app.crimera.patches.instagram.links.distractionFree.disableExplorePatch
import app.crimera.patches.instagram.links.misc.disableDiscoverPeoplePatch
import app.crimera.patches.instagram.misc.distractionFree.disableSwipeToCreatePatch
import app.crimera.patches.instagram.misc.navigation.navigationBarPatch
import app.crimera.patches.instagram.misc.reels.disableReelsScrollingPatch
import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.ACTIVITY_SETTINGS_CLASS
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.instagram.utils.Constants.PREF_DESCRIPTOR
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.p0Register
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference

/*
 * Postbox (Vantage fork only, not upstream piko).
 *
 * Postbox is the locked-down Instagram build: Home (stories tray), Messages and Profile, with
 * no Reels, no Explore and no way to switch any of that back on. Piko already has the blocking
 * features, but each one is a user preference. This patch takes the preference away: it edits
 * piko's own extension dex at patch time so the getters behind those features return constants.
 * It also closes the one place where piko's navigation code deliberately gives up and hands
 * Instagram's own tab list back (see hookNavigationTabsFilter).
 *
 * Everything it touches is extension code (found by class + method name), never an obfuscated
 * Instagram method, so it has no fingerprints of its own. Every lookup throws a PatchException
 * naming what moved: an upstream rename has to fail the build, never ship an unlocked app.
 */

private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val INTENT = "Landroid/content/Intent;"
private const val PREFERENCE_FRAGMENT = "Landroid/preference/PreferenceFragment;"
private const val PREFERENCE_SCREEN = "Landroid/preference/PreferenceScreen;"

private const val POSTBOX_PACKAGE = "$PATCHES_DESCRIPTOR/postbox"
private const val POSTBOX_NAV = "$POSTBOX_PACKAGE/PostboxNav;"
private const val POSTBOX_SETTINGS = "$POSTBOX_PACKAGE/PostboxSettings;"
private const val NAV_CONFIG_FIELD = "NAV_CONFIG"
private const val SANITIZE_STARTUP_INTENT = "sanitizeStartupIntent"
private const val FILTER_NAVIGATION_TABS = "filterNavigationTabs"
private const val SET_PREFERENCE_SCREEN = "setPreferenceScreen"

private const val NAVIGATION_EXTENSION = "$PATCHES_DESCRIPTOR/navigation/NavigationBarPatch;"
private const val PREFLIGHT_STARTUP = "preflightStartup"
private const val TRANSFORM_NAVIGATION_TABS = "transformNavigationTabs"
private const val SETTINGS_FRAGMENT = "$ACTIVITY_SETTINGS_CLASS/SettingsActivity\$SettingsFragment;"

/**
 * `Pref` getters forced to a constant. `true` = the blocking feature is always on.
 *
 * - The first five are the distraction switches (Explore, Reels paging, suggested units,
 *   suggested accounts, swipe-to-create).
 * - `hideNavigation*` and `hasLegacyNavigationSettings` are piko's pre-v2 navigation settings.
 *   They only matter while `navigationTabs()` is empty, which it never is here, but they are
 *   pinned to the same layout so the legacy migration path cannot produce a different bar.
 * - The two Home-header getters stay `false`: the create "+" and the notifications heart are
 *   part of Postbox and must not be hideable from the action bar settings.
 */
private val FORCED_BOOLEAN_GETTERS =
    linkedMapOf(
        "disableExplore" to true,
        "disableReelsScrolling" to true,
        "hideSuggestedContent" to true,
        "disableDiscoverPeople" to true,
        "disableSwipeToCreate" to true,
        "hideNavigationReels" to true,
        "hideNavigationSearch" to true,
        "hideNavigationCreate" to true,
        "hideNavigationFeed" to false,
        "hideNavigationDirect" to false,
        "hasLegacyNavigationSettings" to false,
        "getHideHomeCreateButton" to false,
        "getHideHomeNotificationsButton" to false,
    )

/** The getter that carries piko's navigation layout; forced to PostboxNav.NAV_CONFIG. */
private const val NAVIGATION_TABS_GETTER = "navigationTabs"

private fun Method.parameterTypeNames() = parameterTypes.map(CharSequence::toString)

private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)

/**
 * Finds a public static method of the Postbox extension by exact signature. The patch only
 * references these by name in smali, so a rename on the Java side would otherwise surface as a
 * NoSuchMethodError on the device.
 */
private fun ClassDef.requireStaticMethod(name: String, parameters: List<String>, returnType: String) {
    val found = methods.any {
        it.name == name && it.isStatic() && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
            it.parameterTypeNames() == parameters && it.returnType == returnType
    }
    if (!found) {
        throw PatchException(
            "Postbox: $type has no public static $name(${parameters.joinToString("")})$returnType",
        )
    }
}

/**
 * Reads PostboxNav.NAV_CONFIG out of the extension dex, so the layout string exists in exactly
 * one place (the Java constant) and the patch cannot drift from what the unit tests check.
 */
internal fun readNavConfig(postboxNav: ClassDef): String {
    val field = postboxNav.staticFields.singleOrNull { it.name == NAV_CONFIG_FIELD }
        ?: throw PatchException("Postbox: ${postboxNav.type} has no static field $NAV_CONFIG_FIELD")
    if (field.type != STRING || !AccessFlags.FINAL.isSet(field.accessFlags)) {
        throw PatchException("Postbox: $NAV_CONFIG_FIELD is not a static final String")
    }
    val value = (field.initialValue as? StringEncodedValue)?.value
        ?: throw PatchException(
            "Postbox: $NAV_CONFIG_FIELD has no constant value in the dex. " +
                "It must stay a compile-time String constant",
        )
    if (value.isBlank()) throw PatchException("Postbox: $NAV_CONFIG_FIELD is blank")
    return value
}

/**
 * The one static, no-argument `Pref` getter with this name and return type.
 *
 * A static method that returns a value always has at least one register and, with no
 * parameters, register 0 is a local - which is all [forceConstantReturn] needs. The checks are
 * still explicit so a changed signature fails here, by name.
 */
private fun MutableClass.requireGetter(name: String, returnType: String): MutableMethod {
    val candidates = methods.filter { it.name == name && it.parameterTypes.isEmpty() }
    val getter = candidates.singleOrNull()
        ?: throw PatchException(
            "Postbox: expected exactly one $type->$name() in the extension, found ${candidates.size}. " +
                "Upstream renamed or removed it; the lock must be re-pointed before this can ship",
        )
    if (!getter.isStatic() || getter.returnType != returnType) {
        throw PatchException(
            "Postbox: $type->$name() has an unexpected signature " +
                "(static=${getter.isStatic()}, returns ${getter.returnType}, expected static $returnType)",
        )
    }
    val implementation = getter.implementation
        ?: throw PatchException("Postbox: $type->$name() has no implementation")
    if (implementation.registerCount < 1) {
        throw PatchException("Postbox: $type->$name() has no register to return a constant from")
    }
    return getter
}

/**
 * Makes the method return a constant by putting `const v0` + `return v0` in front of its body.
 *
 * The method object is edited in place rather than swapped for a rebuilt one: MutableClass keeps
 * `methods` and `directMethods` as separate lazily built sets and the dex writer reads the
 * latter, so replacing the object is only safe while nobody has materialised that set yet. An
 * in-place edit has no such ordering hazard. The old body stays behind as unreachable code,
 * which is what Morphe's own `returnEarly` leaves too; unlike a rewrite of instruction 0 this
 * does not care how long the getter is, what its registers hold, or whether it has try blocks.
 */
private fun MutableMethod.forceConstantReturn(constant: BuilderInstruction, returnOpcode: Opcode) {
    addInstructions(0, listOf(constant, BuilderInstruction11x(returnOpcode, 0)))
    val head = instructions.take(2).map { it.opcode }
    if (head != listOf(constant.opcode, returnOpcode)) {
        throw PatchException("Postbox: failed to force $definingClass->$name(), method starts with $head")
    }
}

/** L4: every blocking preference becomes a build-time constant. */
internal fun lockPrefGetters(pref: MutableClass, navConfig: String) {
    // Resolve everything first so a missing getter aborts before any of them is edited.
    val booleans = FORCED_BOOLEAN_GETTERS.map { (name, value) -> pref.requireGetter(name, "Z") to value }
    val navigationTabs = pref.requireGetter(NAVIGATION_TABS_GETTER, STRING)

    booleans.forEach { (getter, value) ->
        getter.forceConstantReturn(
            BuilderInstruction11n(Opcode.CONST_4, 0, if (value) 1 else 0),
            Opcode.RETURN,
        )
    }
    navigationTabs.forceConstantReturn(
        BuilderInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(navConfig)),
        Opcode.RETURN_OBJECT,
    )
}

/**
 * L3: piko's injected code calls `NavigationBarPatch.preflightStartup(Intent, boolean)` as the
 * very first instruction of Instagram's startup method, on every pass and with the live Intent,
 * before Instagram reads the startup-tab extra. Piko itself only acts when the extra is absent;
 * prepending the Postbox sanitizer to that extension method covers the case where it is present,
 * without a second copy of piko's fingerprints for the obfuscated Instagram method.
 */
internal fun hookStartupSanitizer(navigation: MutableClass) {
    val candidates = navigation.methods.filter { it.name == PREFLIGHT_STARTUP }
    val preflight = candidates.singleOrNull()
        ?: throw PatchException(
            "Postbox: expected exactly one ${navigation.type}->$PREFLIGHT_STARTUP, found ${candidates.size}. " +
                "The startup-tab guard has nowhere to hook",
        )
    if (!preflight.isStatic() || preflight.parameterTypeNames() != listOf(INTENT, "Z") ||
        preflight.returnType != "Z" || preflight.implementation == null
    ) {
        throw PatchException("Postbox: ${navigation.type}->$PREFLIGHT_STARTUP has an unexpected signature")
    }
    // Static method: p0 is the Intent. The range form takes any register number.
    val intentRegister = preflight.p0Register
    preflight.addInstruction(
        0,
        "invoke-static/range { v$intentRegister .. v$intentRegister }, " +
            "$POSTBOX_NAV->$SANITIZE_STARTUP_INTENT($INTENT)V",
    )
}

/**
 * Fail-closed bottom bar: every `return` of piko's
 * `NavigationBarPatch.transformNavigationTabs(List, List)` hands its list to
 * `PostboxNav.filterNavigationTabs` first, which drops whatever is not Home, Messages or Profile.
 *
 * Piko's method fails OPEN by design: when it throws internally, or gets a native list it will
 * not touch, it returns Instagram's own tabs, Reels and Search included. The forced config
 * above only governs the path where piko succeeds; this covers the ones where it does not,
 * including its `catch (Throwable)` handler, which is just another `return-object`.
 *
 * The call goes in with addInstructionsAtControlFlowLabel, not a plain insert: a branch (or a
 * catch handler) that jumps straight to a `return-object` would land AFTER code inserted in
 * front of it and skip the filter. That helper moves the instruction's labels onto the new
 * code. Afterwards every return is checked to be fed by the filter with nothing jumping in
 * between.
 */
internal fun hookNavigationTabsFilter(navigation: MutableClass) {
    val candidates = navigation.methods.filter { it.name == TRANSFORM_NAVIGATION_TABS }
    val transform = candidates.singleOrNull()
        ?: throw PatchException(
            "Postbox: expected exactly one ${navigation.type}->$TRANSFORM_NAVIGATION_TABS, found ${candidates.size}. " +
                "The fail-closed tab filter has nowhere to hook",
        )
    if (!transform.isStatic() || transform.parameterTypeNames() != listOf(LIST, LIST) ||
        transform.returnType != LIST || transform.implementation == null
    ) {
        throw PatchException("Postbox: ${navigation.type}->$TRANSFORM_NAVIGATION_TABS has an unexpected signature")
    }

    val filterCall = "$POSTBOX_NAV->$FILTER_NAVIGATION_TABS($LIST)$LIST"
    val returns = transform.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index }
    if (returns.isEmpty()) {
        throw PatchException("Postbox: ${navigation.type}->$TRANSFORM_NAVIGATION_TABS never returns a list")
    }
    // Last return first, so the indexes of the earlier ones stay valid.
    returns.asReversed().forEach { index ->
        val register = (transform.instructions[index] as OneRegisterInstruction).registerA
        // The range form takes any register number, and so does move-result-object.
        transform.addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$register .. v$register }, $filterCall
                move-result-object v$register
            """.trimIndent(),
        )
    }

    val patched = transform.instructions
    val patchedReturns = patched.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
    if (patchedReturns.size != returns.size) {
        throw PatchException("Postbox: the tab filter changed the number of returns in $TRANSFORM_NAVIGATION_TABS")
    }
    patchedReturns.forEach { (index, instruction) ->
        val register = (instruction as OneRegisterInstruction).registerA
        val moveResult = patched.getOrNull(index - 1)
        val call = patched.getOrNull(index - 2)
        val reference = (call as? ReferenceInstruction)?.reference as? MethodReference
        val filtered = moveResult?.opcode == Opcode.MOVE_RESULT_OBJECT &&
            (moveResult as OneRegisterInstruction).registerA == register &&
            call?.opcode == Opcode.INVOKE_STATIC_RANGE &&
            (call as RegisterRangeInstruction).startRegister == register &&
            reference?.definingClass == POSTBOX_NAV && reference.name == FILTER_NAVIGATION_TABS
        // A label on the return or on the move-result means something can still jump past the call.
        val reachableOnlyThroughFilter = instruction.location.labels.isEmpty() &&
            moveResult?.location?.labels?.isEmpty() == true
        if (!filtered || !reachableOnlyThroughFilter) {
            throw PatchException(
                "Postbox: a return of ${navigation.type}->$TRANSFORM_NAVIGATION_TABS (instruction $index) " +
                    "is not behind the tab filter",
            )
        }
    }
}

/**
 * Settings hygiene: route the single `setPreferenceScreen(screen)` call of piko's settings
 * fragment through PostboxSettings, which strips the rows for the forced prefs and the
 * navigation editor from the finished screen. The instruction is replaced one-for-one (same
 * registers, same size), so branches that target it still do.
 */
internal fun hookSettingsPrune(settingsFragment: MutableClass) {
    val onCreate = settingsFragment.methods.singleOrNull {
        it.name == "onCreate" && it.parameterTypeNames() == listOf("Landroid/os/Bundle;") && it.returnType == "V"
    } ?: throw PatchException("Postbox: ${settingsFragment.type} has no onCreate(Bundle)")

    val calls = onCreate.instructions.withIndex().filter { (_, instruction) ->
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        reference != null && reference.name == SET_PREFERENCE_SCREEN &&
            reference.parameterTypes.map(CharSequence::toString) == listOf(PREFERENCE_SCREEN) &&
            reference.returnType == "V"
    }
    val call = calls.singleOrNull()
        ?: throw PatchException(
            "Postbox: expected one setPreferenceScreen call in ${settingsFragment.type}->onCreate, found ${calls.size}",
        )

    val target = "$POSTBOX_SETTINGS->$SET_PREFERENCE_SCREEN($PREFERENCE_FRAGMENT$PREFERENCE_SCREEN)V"
    val instruction = call.value
    val replacement = when (instruction.opcode) {
        Opcode.INVOKE_VIRTUAL -> {
            instruction as FiveRegisterInstruction
            if (instruction.registerCount != 2) {
                throw PatchException("Postbox: setPreferenceScreen call uses ${instruction.registerCount} registers")
            }
            "invoke-static { v${instruction.registerC}, v${instruction.registerD} }, $target"
        }

        Opcode.INVOKE_VIRTUAL_RANGE -> {
            instruction as RegisterRangeInstruction
            if (instruction.registerCount != 2) {
                throw PatchException("Postbox: setPreferenceScreen call uses ${instruction.registerCount} registers")
            }
            val first = instruction.startRegister
            "invoke-static/range { v$first .. v${first + 1} }, $target"
        }

        else -> throw PatchException("Postbox: setPreferenceScreen is called with ${instruction.opcode}")
    }
    onCreate.replaceInstruction(call.index, replacement)
}

@Suppress("unused")
val postboxSettingsLockPatch =
    bytecodePatch(
        name = "Postbox settings lock",
        description = "Postbox builds only. Hardcodes the bottom bar to Home, Messages and Profile " +
            "(opening on Messages) and forces Explore, Reels scrolling, suggested content, suggested " +
            "accounts and swipe-to-create off, so no setting, backup restore or Focus Lock state can " +
            "re-enable them. Startup intents that name the Reels or Search tab land on Messages instead, " +
            "and the bar is filtered down to those three tabs even where piko falls back to Instagram's own list.",
        default = false,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        // The patches whose preferences are forced. Depending on them guarantees the blocking
        // code and its SettingsStatus flag are present whenever the lock is.
        dependsOn(
            settingsPatch,
            navigationBarPatch,
            disableExplorePatch,
            disableReelsScrollingPatch,
            hideSuggestedContentPatch,
            disableDiscoverPeoplePatch,
            disableSwipeToCreatePatch,
        )

        execute {
            fun mutableExtensionClass(type: String) =
                mutableClassDefByOrNull(type)
                    ?: throw PatchException("Postbox: extension class $type not found")

            val postboxNav = classDefByOrNull(POSTBOX_NAV)
                ?: throw PatchException("Postbox: extension class $POSTBOX_NAV not found")
            val postboxSettings = classDefByOrNull(POSTBOX_SETTINGS)
                ?: throw PatchException("Postbox: extension class $POSTBOX_SETTINGS not found")
            postboxNav.requireStaticMethod(SANITIZE_STARTUP_INTENT, listOf(INTENT), "V")
            postboxNav.requireStaticMethod(FILTER_NAVIGATION_TABS, listOf(LIST), LIST)
            postboxSettings.requireStaticMethod(
                SET_PREFERENCE_SCREEN,
                listOf(PREFERENCE_FRAGMENT, PREFERENCE_SCREEN),
                "V",
            )
            val navConfig = readNavConfig(postboxNav)

            // Resolve all three targets before editing any, so a failure leaves nothing half done.
            val pref = mutableExtensionClass(PREF_DESCRIPTOR)
            val navigation = mutableExtensionClass(NAVIGATION_EXTENSION)
            val settingsFragment = mutableExtensionClass(SETTINGS_FRAGMENT)

            lockPrefGetters(pref, navConfig)
            hookStartupSanitizer(navigation)
            hookNavigationTabsFilter(navigation)
            hookSettingsPrune(settingsFragment)
        }
    }
