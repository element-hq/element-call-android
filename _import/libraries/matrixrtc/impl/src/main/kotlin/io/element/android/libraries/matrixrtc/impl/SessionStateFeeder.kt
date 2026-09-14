/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixrtc.impl

import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrixrtc.api.MatrixRtcEventTypes
import io.element.android.libraries.matrixrtc.impl.bridge.widget.ToDeviceRelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import uniffi.matrix_rtc_ffi.RtcSessionManagerHandleInterface

/**
 * The part of the inbound bridge that is not tied to a call: it runs for as long as the Matrix
 * session does.
 *
 * Only RTC media keys live here, and they live here because to-device delivery cannot be caught up
 * on. [RoomStateFeeder] handles what is room-scoped, and that genuinely can start at join time -
 * sticky events and room state are snapshots, so a late subscriber is handed the current truth.
 * A to-device message is not: it goes to whoever is subscribed when it arrives and is then forgotten.
 * Subscribing per call would silently discard every key sent while we were between calls, including
 * the rotation another member performs the moment they see us join.
 *
 * The subscription is session-long, but what feeds it is not: the [relay] carries messages from
 * whichever room bridge is live, and the widget-driver bridge only runs during a call. A key sent while
 * no call is up is lost; peers re-send on join, which is why this is acceptable for the stopgap. An
 * SDK-backed to-device subscription would restore the full guarantee without touching this class.
 */
internal class SessionStateFeeder(
    private val manager: RtcSessionManagerHandleInterface,
    private val relay: ToDeviceRelay,
    private val scope: CoroutineScope,
) {
    fun start() {
        feedEncryptionKeys()
        feedElementCallEncryptionKeys()
    }

    /**
     * Per-participant media keys arrive over to-device. Only encrypted messages are trusted: the
     * top-level sender of a cleartext to-device message is unauthenticated, so accepting one would
     * let anyone inject a media key.
     */
    private fun feedEncryptionKeys() {
        relay.subscribe(listOf(MatrixRtcEventTypes.ENCRYPTION_KEY))
            .onEach { message ->
                val key = EncryptionKeyMapper.map(message)
                if (key == null) {
                    Timber.w("MatrixRTC: ignoring unusable encryption key from ${message.senderId}")
                } else {
                    // `crossSigned` is the field that makes the core throw a key away, and it does so
                    // without telling us: the member just stays at MISSING_KEY for the rest of the
                    // call. Logging what we claimed separates "the key never arrived" from "we handed
                    // it over and said not to trust it".
                    Timber.i(
                        "MatrixRTC: encryption key for ${key.memberId} index ${key.keyIndex} from " +
                            "${key.senderUserId}/${key.senderDeviceId} crossSigned=${key.senderIsCrossSigned}"
                    )
                    feed(key.memberId) { manager.receiveEncryptionKey(key) }
                }
            }
            .launchIn(scope)
    }

    /**
     * The same keys as [feedEncryptionKeys], as Element Call sends them.
     *
     * A to-device message carries exactly one type, so a peer speaking that dialect sends its keys
     * under `io.element.call.encryption_keys` *instead of* the spec type, with a `keys` array rather
     * than a single `media_key`. The library parses that content itself, so it goes over raw.
     *
     * Subscribed unconditionally rather than per compatibility mode, for the reason this whole class
     * exists: a to-device message is delivered once, to whoever is subscribed at that moment, and
     * cannot be caught up on. The mode is chosen when a call is placed, which is far too late - the
     * key we would miss is the one the far side rotates the instant it sees us join. Feeding a key
     * for a call we are not in costs nothing: with no membership to bind it to, the core has nowhere
     * to put it.
     */
    private fun feedElementCallEncryptionKeys() {
        relay.subscribe(listOf(MatrixRtcEventTypes.ENCRYPTION_KEY_ELEMENT_CALL))
            .onEach { message ->
                // Same rule as the spec type: a cleartext to-device message has no attested sender, so
                // anyone could claim to be a participant and inject a media key. The device is passed
                // through as the library made it nullable - it is the side that decides what an
                // unattested device disqualifies a key from.
                val encryptionInfo = message.encryptionInfo ?: run {
                    Timber.w("MatrixRTC: dropping a cleartext Element Call encryption key")
                    return@onEach
                }
                val senderDeviceId = encryptionInfo.senderDeviceId
                Timber.i(
                    "MatrixRTC: Element Call encryption key from ${encryptionInfo.senderId}/$senderDeviceId " +
                        "crossSigned=${encryptionInfo.isSenderCrossSigned}"
                )
                feed(encryptionInfo.senderId.value) {
                    manager.receiveLegacyEncryptionKey(
                        // The attested sender, not the one claimed in the content.
                        sender = encryptionInfo.senderId.value,
                        contentJson = message.content,
                        wasEncrypted = true,
                        senderDeviceId = senderDeviceId?.value,
                        senderIsCrossSigned = encryptionInfo.isSenderCrossSigned,
                    )
                }
            }
            .launchIn(scope)
    }

    /**
     * Hand a key to the core without letting its failure reach the scope.
     *
     * Same reasoning as the feed in [RoomStateFeeder]: uniffi surfaces anything that goes wrong
     * inside the core as an exception, which uncaught would kill the process from a background
     * flow. Losing one key is recoverable - the sender rotates again on the next membership
     * change - whereas losing the process takes the call and the log with it.
     */
    private suspend fun feed(memberId: String, block: suspend () -> Unit) {
        runCatchingExceptions { block() }.onFailure {
            Timber.e(it, "MatrixRTC: core rejected the encryption key for $memberId")
        }
    }
}
