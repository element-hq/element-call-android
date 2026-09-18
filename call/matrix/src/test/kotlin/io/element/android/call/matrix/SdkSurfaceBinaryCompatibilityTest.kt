/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.matrix

import com.google.common.truth.Truth.assertWithMessage
import io.element.android.call.matrix.util.runCatchingExceptions
import org.junit.Test

/**
 * Every member of the Matrix Rust SDK this module calls, as JVM descriptors, resolved by reflection
 * against whatever SDK is on the test runtime classpath.
 *
 * Not a behaviour test. A published AAR is binary where a source dependency is not: uniffi regenerates
 * every signature per SDK release, so a parameter added to a function changes its descriptor and its
 * `$default` synthetic, and a method added to an interface we implement is an `AbstractMethodError`
 * when the SDK calls it. Compiling against the newer SDK catches neither for the bytecode a host
 * actually runs, which is ours against *their* SDK. `sdk-compat.yml` runs this test twice: compiled
 * against the pinned SDK with the newest one forced onto the runtime classpath (Element X's situation
 * the month after it bumps), and compiled against the newest (what the next pin looks like). A red run
 * is the week the bindings moved, not a crash report from a host. See AGENTS.md, "The SDK edge".
 *
 * Kotlin mangles the names of functions taking or returning inline classes (`UInt`, `ULong`), so a
 * member is matched on its name or on the name followed by a `-` and the hash.
 */
class SdkSurfaceBinaryCompatibilityTest {
    @Test
    fun `every SDK type this module names exists`() {
        SdkSurface.types.forEach { className ->
            assertWithMessage("SDK type $className").that(runCatchingExceptions { Class.forName(className) }.isSuccess).isTrue()
        }
    }

    @Test
    fun `every SDK member this module calls has the descriptor it was compiled against`() {
        SdkSurface.members.forEach { member ->
            val type = Class.forName(member.className)
            val found = if (member.name == SdkMember.CONSTRUCTOR) {
                type.declaredConstructors.any { it.parameterTypes.map(Class<*>::getSimpleName) == member.parameterTypes }
            } else {
                type.methods.any { method ->
                    (method.name == member.name || method.name.startsWith("${member.name}-")) &&
                        method.parameterTypes.map(Class<*>::getSimpleName) == member.parameterTypes
                }
            }
            assertWithMessage("$member on the SDK at test runtime").that(found).isTrue()
        }
    }

    @Test
    fun `every SDK enum constant this module names exists`() {
        SdkSurface.enumConstants.forEach { (className, constant) ->
            val constants = Class.forName(className).enumConstants.orEmpty().map { (it as Enum<*>).name }
            assertWithMessage("$className.$constant").that(constants).contains(constant)
        }
    }
}

/** One SDK member by its JVM shape: the owning class, the name, the parameter types' simple names. */
data class SdkMember(val className: String, val name: String, val parameterTypes: List<String>) {
    override fun toString(): String = "$className.$name(${parameterTypes.joinToString()})"

    companion object {
        const val CONSTRUCTOR = "<init>"
    }
}

/**
 * The whole of what `call/matrix` uses from the SDK. Kept next to the test that reads it rather than
 * derived, so that adding an SDK call to the module is a two-line change reviewers see.
 */
object SdkSurface {
    private const val SDK = "org.matrix.rustcomponents.sdk"
    private const val CONTINUATION = "Continuation"

    val types = listOf(
        "$SDK.Client",
        "$SDK.Room",
        "$SDK.RoomInfo",
        "$SDK.RoomInfoListener",
        "$SDK.RoomMember",
        "$SDK.RoomMembersIterator",
        "$SDK.MembershipState\$Join",
        "$SDK.OpenIdToken",
        "$SDK.ClientException\$MatrixApi",
        "$SDK.TaskHandle",
        "$SDK.WidgetSettings",
        "$SDK.WidgetDriver",
        "$SDK.WidgetDriverHandle",
        "$SDK.WidgetDriverAndHandle",
        "$SDK.WidgetCapabilities",
        "$SDK.WidgetCapabilitiesProvider",
        "$SDK.WidgetEventFilter\$StateWithType",
        "$SDK.WidgetEventFilter\$ToDevice",
        "$SDK.WidgetEventFilter\$MessageLikeWithType",
        "uniffi.matrix_sdk_base.EncryptionState",
    )

    val members = listOf(
        // ElementCallSdkTransport
        SdkMember("$SDK.Client", "userId", emptyList()),
        SdkMember("$SDK.Client", "deviceId", emptyList()),
        SdkMember("$SDK.Client", "homeserver", emptyList()),
        SdkMember("$SDK.Client", "userIdServerName", emptyList()),
        SdkMember("$SDK.Client", "getUrl", listOf("String", CONTINUATION)),
        SdkMember("$SDK.Client", "requestOpenidToken", listOf(CONTINUATION)),
        SdkMember("$SDK.Client", "getRoom", listOf("String")),
        SdkMember("$SDK.OpenIdToken", "getAccessToken", emptyList()),
        SdkMember("$SDK.OpenIdToken", "getTokenType", emptyList()),
        SdkMember("$SDK.OpenIdToken", "getMatrixServerName", emptyList()),
        SdkMember("$SDK.OpenIdToken", "getExpiresInSeconds", emptyList()),
        // SdkElementCallMatrixRoom, SdkRoomInfo, SdkElementCallRoomContext
        SdkMember("$SDK.Room", "subscribeToRoomInfoUpdates", listOf("RoomInfoListener")),
        SdkMember("$SDK.Room", "roomInfo", listOf(CONTINUATION)),
        SdkMember("$SDK.Room", "members", listOf(CONTINUATION)),
        SdkMember("$SDK.Room", "membersNoSync", listOf(CONTINUATION)),
        SdkMember("$SDK.Room", "sendStateEventRaw", listOf("String", "String", "String", CONTINUATION)),
        SdkMember("$SDK.RoomInfoListener", "call", listOf("RoomInfo")),
        SdkMember("$SDK.TaskHandle", "cancel", emptyList()),
        SdkMember("$SDK.TaskHandle", "close", emptyList()),
        SdkMember("$SDK.RoomInfo", "getDisplayName", emptyList()),
        SdkMember("$SDK.RoomInfo", "isDm", emptyList()),
        SdkMember("$SDK.RoomInfo", "getEncryptionState", emptyList()),
        SdkMember("$SDK.RoomInfo", "getJoinedMembersCount", emptyList()),
        SdkMember("$SDK.RoomMembersIterator", "len", emptyList()),
        SdkMember("$SDK.RoomMembersIterator", "nextChunk", listOf("int")),
        SdkMember("$SDK.RoomMember", "getUserId", emptyList()),
        SdkMember("$SDK.RoomMember", "getDisplayName", emptyList()),
        SdkMember("$SDK.RoomMember", "getAvatarUrl", emptyList()),
        SdkMember("$SDK.RoomMember", "getMembership", emptyList()),
        // SdkFailures
        SdkMember("$SDK.ClientException\$MatrixApi", "getCode", emptyList()),
        SdkMember("$SDK.ClientException\$MatrixApi", "getMsg", emptyList()),
        // temporary/widget: SdkWidgetDriver and WidgetCapabilityGrant
        SdkMember("$SDK.Matrix_sdk_ffiKt", "makeWidgetDriver", listOf("WidgetSettings")),
        SdkMember("$SDK.WidgetSettings", SdkMember.CONSTRUCTOR, listOf("String", "boolean", "String")),
        SdkMember("$SDK.WidgetDriverAndHandle", "getDriver", emptyList()),
        SdkMember("$SDK.WidgetDriverAndHandle", "getHandle", emptyList()),
        SdkMember("$SDK.WidgetDriver", "run", listOf("Room", "WidgetCapabilitiesProvider", CONTINUATION)),
        SdkMember("$SDK.WidgetDriver", "close", emptyList()),
        SdkMember("$SDK.WidgetDriverHandle", "recv", listOf(CONTINUATION)),
        SdkMember("$SDK.WidgetDriverHandle", "send", listOf("String", CONTINUATION)),
        SdkMember("$SDK.WidgetDriverHandle", "close", emptyList()),
        SdkMember("$SDK.WidgetCapabilitiesProvider", "acquireCapabilities", listOf("WidgetCapabilities")),
        // The exact arity matters: `copy` with a new field is a different descriptor and a different `copy$default`.
        SdkMember("$SDK.WidgetCapabilities", "copy", listOf("List", "List", "boolean", "boolean", "boolean", "boolean", "boolean")),
        SdkMember("$SDK.WidgetEventFilter\$StateWithType", SdkMember.CONSTRUCTOR, listOf("String")),
        SdkMember("$SDK.WidgetEventFilter\$ToDevice", SdkMember.CONSTRUCTOR, listOf("String")),
        SdkMember("$SDK.WidgetEventFilter\$MessageLikeWithType", SdkMember.CONSTRUCTOR, listOf("String")),
    )

    val enumConstants = listOf(
        "uniffi.matrix_sdk_base.EncryptionState" to "ENCRYPTED",
        "uniffi.matrix_sdk_base.EncryptionState" to "NOT_ENCRYPTED",
        "uniffi.matrix_sdk_base.EncryptionState" to "UNKNOWN",
    )
}
