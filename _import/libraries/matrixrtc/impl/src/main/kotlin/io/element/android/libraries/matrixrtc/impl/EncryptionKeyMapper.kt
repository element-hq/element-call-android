/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl

import io.element.android.libraries.matrixrtc.impl.bridge.MatrixRtcToDeviceMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import timber.log.Timber
import uniffi.matrix_rtc_ffi.FfiReceivedEncryptionKey

/**
 * Turns an incoming media-key to-device message into the record the RTC core expects.
 */
internal object EncryptionKeyMapper {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @return the key, or null if the message is untrusted or unusable.
     */
    fun map(message: MatrixRtcToDeviceMessage): FfiReceivedEncryptionKey? {
        // A cleartext to-device message has no attested sender, so anyone could claim to be a
        // participant and inject a media key. Never accept one.
        val encryptionInfo = message.encryptionInfo ?: run {
            Timber.w("MatrixRTC: dropping cleartext encryption key")
            return null
        }
        val senderDeviceId = encryptionInfo.senderDeviceId ?: run {
            Timber.w("MatrixRTC: dropping encryption key with no sender device")
            return null
        }

        val content = try {
            json.parseToJsonElement(message.content) as? JsonObject
        } catch (throwable: Throwable) {
            Timber.w(throwable, "MatrixRTC: cannot parse encryption key content")
            null
        } ?: return null

        val roomId = content.string("room_id") ?: return null
        val memberId = content.string("member_id") ?: return null
        // The key is nested: `{"format":0,"media_key":{"index":1,"key":"..."},...}`. `format` names
        // the media_key encoding rather than the envelope, so it is the core's to interpret, and an
        // unknown one still has to reach it - dropping here would look like a missing key.
        val mediaKey = content["media_key"] as? JsonObject ?: run {
            Timber.w("MatrixRTC: encryption key has no media_key")
            return null
        }
        val keyB64 = mediaKey.string("key") ?: return null
        val keyIndex = mediaKey.int("index") ?: return null

        return FfiReceivedEncryptionKey(
            roomId = roomId,
            memberId = memberId,
            keyB64 = keyB64,
            keyIndex = keyIndex.toUByte(),
            wasEncrypted = true,
            // The attested sender, not the one claimed in the event.
            senderUserId = encryptionInfo.senderId.value,
            senderDeviceId = senderDeviceId.value,
            senderIsCrossSigned = encryptionInfo.isSenderCrossSigned,
        )
    }

    /**
     * The `media_key.index` of a key we are about to send, for logging only.
     *
     * Lives here rather than at the call site so the envelope shape is known in exactly one place. It
     * exists because the index we distribute and the index our own frame cryptor is using have been
     * observed to disagree - a receiver faithfully installing what it is told still cannot decrypt if
     * the sender is encrypting with an older index - and without this the two are only comparable by
     * lining up two devices' logs and guessing.
     *
     * @return null for anything unparseable, including a message type that carries no media key at
     * all. A log line is never worth failing a send over.
     */
    fun outgoingKeyIndex(contentJson: String): Int? = try {
        val content = json.parseToJsonElement(contentJson) as? JsonObject
        (content?.get("media_key") as? JsonObject)?.int("index")
    } catch (throwable: Throwable) {
        Timber.d(throwable, "MatrixRTC: cannot read the outgoing key index")
        null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
