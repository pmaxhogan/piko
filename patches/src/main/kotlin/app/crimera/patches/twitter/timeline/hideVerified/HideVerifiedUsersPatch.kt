/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.twitter.timeline.hideVerified

import app.crimera.patches.twitter.utils.Constants.COMPATIBILITY_X
import app.crimera.patches.twitter.utils.Constants.PATCHES_DESCRIPTOR
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

private const val JACKSON_CLASS = "/fasterxml/jackson/core/"

// Same hook point as "Log server response": the Jackson factory method that turns
// a response InputStream into a parser. We wrap the stream so the raw JSON is
// filtered before the app ever parses it - independent of the app's obfuscated
// model, and uniform across the home timeline, replies, and search.
private object JacksonInputStreamFingerprint : Fingerprint(
    definingClass = JACKSON_CLASS,
    parameters = listOf("Ljava/io/InputStream"),
    custom = { methodDef, _ ->
        methodDef.returnType.contains(JACKSON_CLASS)
    },
)

@Suppress("unused")
val hideVerifiedUsersPatch =
    bytecodePatch(
        name = "Hide verified users",
        description = "Hides tweets and replies from accounts with a verified check " +
            "(blue / X Premium, including a hidden checkmark, plus gold/grey org and " +
            "legacy verified).",
    ) {
        compatibleWith(COMPATIBILITY_X)

        execute {
            JacksonInputStreamFingerprint.method.addInstructions(
                0,
                """
                invoke-static {p1}, $PATCHES_DESCRIPTOR/HideVerified;->filter(Ljava/io/InputStream;)Ljava/io/InputStream;
                move-result-object p1
                """.trimIndent(),
            )
        }
    }
