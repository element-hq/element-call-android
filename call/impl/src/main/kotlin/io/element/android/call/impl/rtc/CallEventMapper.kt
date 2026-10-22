/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import io.element.android.call.api.rtc.MatrixRtcCallEnd
import io.element.android.call.api.rtc.MatrixRtcCallEvent
import io.element.android.call.api.rtc.MatrixRtcCallStatus
import io.element.android.call.api.rtc.MatrixRtcConnectionLossReason
import io.element.android.call.api.rtc.MatrixRtcConnectionState
import io.element.android.call.api.rtc.MatrixRtcDetailWindow
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionDiagnostic
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionState
import io.element.android.call.api.rtc.MatrixRtcInterruptions
import io.element.android.call.api.rtc.MatrixRtcKeyRejection
import io.element.android.call.api.rtc.MatrixRtcLocalState
import io.element.android.call.api.rtc.MatrixRtcMediaPhase
import io.element.android.call.api.rtc.MatrixRtcMembership
import io.element.android.call.api.rtc.MatrixRtcReceiveStats
import io.element.android.call.api.rtc.MatrixRtcRingPhase
import io.element.android.call.api.rtc.MatrixRtcSignallingHealth
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcStreamRef
import io.element.android.call.api.rtc.MatrixRtcTile
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.api.rtc.MatrixRtcTileRef
import io.element.android.call.api.rtc.MatrixRtcTileRoster
import io.element.android.call.api.rtc.MatrixRtcUnableToDecrypt
import io.element.android.call.api.rtc.id.UserId
import org.matrix.rtc.FfiCallEnd
import org.matrix.rtc.FfiCallEvent
import org.matrix.rtc.FfiCallStatus
import org.matrix.rtc.FfiCallTile
import org.matrix.rtc.FfiConnectionLossReason
import org.matrix.rtc.FfiConnectionState
import org.matrix.rtc.FfiFrameEncryptionDiagnostic
import org.matrix.rtc.FfiFrameEncryptionState
import org.matrix.rtc.FfiInterruptions
import org.matrix.rtc.FfiKeyRejection
import org.matrix.rtc.FfiLocalState
import org.matrix.rtc.FfiMediaPhase
import org.matrix.rtc.FfiReceiveStats
import org.matrix.rtc.FfiRingPhase
import org.matrix.rtc.FfiSignallingHealth
import org.matrix.rtc.FfiStreamKind
import org.matrix.rtc.FfiStreamRef
import org.matrix.rtc.FfiStreamStats
import org.matrix.rtc.FfiTileId
import org.matrix.rtc.FfiTileKind
import org.matrix.rtc.FfiTileRef
import org.matrix.rtc.FfiTileRoster
import org.matrix.rtc.FfiUnableToDecrypt
import org.matrix.rtc.JoinedMembership

/**
 * @return null for an event the library has no type for yet: reactions, which are read and dropped here until
 * the library shows them.
 */
internal fun FfiCallEvent.map(): MatrixRtcCallEvent? = when (this) {
    is FfiCallEvent.ParticipantJoined -> MatrixRtcCallEvent.ParticipantJoined(memberId, UserId(userId), participantCount.toInt())
    is FfiCallEvent.ParticipantLeft -> MatrixRtcCallEvent.ParticipantLeft(memberId, participantCount.toInt())
    is FfiCallEvent.StreamStarted -> MatrixRtcCallEvent.StreamStarted(memberId, kind.map())
    is FfiCallEvent.StreamStopped -> MatrixRtcCallEvent.StreamStopped(memberId, kind.map())
    is FfiCallEvent.StreamMuted -> MatrixRtcCallEvent.StreamMuted(memberId, kind.map())
    is FfiCallEvent.StreamUnmuted -> MatrixRtcCallEvent.StreamUnmuted(memberId, kind.map())
    is FfiCallEvent.KeyImported -> MatrixRtcCallEvent.KeyImported(memberId, keyIndex.toInt())
    is FfiCallEvent.KeyDiscarded -> MatrixRtcCallEvent.KeyDiscarded(
        memberId = memberId,
        keyIndex = keyIndex?.toInt(),
        senderUserId = senderUserId?.let(::UserId),
        senderDeviceId = senderDeviceId,
        reason = reason.map(),
    )
    is FfiCallEvent.FrameEncryptionState -> MatrixRtcCallEvent.FrameEncryption(memberId, state.map(), diagnostic.map())
    is FfiCallEvent.UnknownParticipant -> MatrixRtcCallEvent.UnknownParticipant(identity)
    is FfiCallEvent.HandRaised -> MatrixRtcCallEvent.HandRaised(memberId)
    is FfiCallEvent.HandLowered -> MatrixRtcCallEvent.HandLowered(memberId)
    is FfiCallEvent.Reaction -> null
}

internal fun FfiKeyRejection.map(): MatrixRtcKeyRejection = when (this) {
    is FfiKeyRejection.Cleartext -> MatrixRtcKeyRejection.Cleartext
    is FfiKeyRejection.SenderNotConfirmed -> MatrixRtcKeyRejection.SenderNotConfirmed
    is FfiKeyRejection.UnverifiableDevice -> MatrixRtcKeyRejection.UnverifiableDevice
    is FfiKeyRejection.RoomMismatch -> MatrixRtcKeyRejection.RoomMismatch(claimed)
    is FfiKeyRejection.SenderMismatch -> MatrixRtcKeyRejection.SenderMismatch(expected, actual)
    is FfiKeyRejection.DeviceMismatch -> MatrixRtcKeyRejection.DeviceMismatch(expected, actual)
}

internal fun FfiFrameEncryptionDiagnostic.map(): MatrixRtcFrameEncryptionDiagnostic = when (this) {
    is FfiFrameEncryptionDiagnostic.NotApplicable -> MatrixRtcFrameEncryptionDiagnostic.NotApplicable
    is FfiFrameEncryptionDiagnostic.NoKeyInstalled -> MatrixRtcFrameEncryptionDiagnostic.NoKeyInstalled
    is FfiFrameEncryptionDiagnostic.KeysInstalled -> MatrixRtcFrameEncryptionDiagnostic.KeysInstalled(keyIndices.map { it.toInt() })
}

/**
 * The core's membership projection for one member.
 *
 * `senderDeviceId` is only known for a membership that arrived encrypted - MSC4143 has no
 * self-asserted device id - so it stays nullable rather than being flattened to a placeholder.
 */
internal fun JoinedMembership.map() = MatrixRtcMembership(
    memberId = memberId,
    userId = UserId(sender),
    deviceId = senderDeviceId,
    application = application,
)

internal fun FfiFrameEncryptionState.map(): MatrixRtcFrameEncryptionState = when (this) {
    FfiFrameEncryptionState.OK -> MatrixRtcFrameEncryptionState.OK
    FfiFrameEncryptionState.MISSING_KEY -> MatrixRtcFrameEncryptionState.MISSING_KEY
    FfiFrameEncryptionState.DECRYPTION_FAILED -> MatrixRtcFrameEncryptionState.DECRYPTION_FAILED
    FfiFrameEncryptionState.ENCRYPTION_FAILED -> MatrixRtcFrameEncryptionState.ENCRYPTION_FAILED
    FfiFrameEncryptionState.INTERNAL_ERROR -> MatrixRtcFrameEncryptionState.INTERNAL_ERROR
}

/**
 * The counters are unsigned across the FFI but only ever grow from zero, so a signed [Long] holds
 * them with room to spare. `packetsLost` is already signed - the transport reports it as a delta
 * against expected, which can go negative when duplicates arrive.
 */
internal fun FfiReceiveStats.map() = MatrixRtcReceiveStats(
    packetsReceived = packetsReceived.toLong(),
    packetsLost = packetsLost,
    bytesReceived = bytesReceived.toLong(),
    jitter = jitter,
    framesDecoded = framesDecoded.toLong(),
    framesDropped = framesDropped.toLong(),
    totalSamplesReceived = totalSamplesReceived.toLong(),
    concealedSamples = concealedSamples.toLong(),
    silentConcealedSamples = silentConcealedSamples.toLong(),
    concealmentEvents = concealmentEvents.toLong(),
)

internal fun FfiStreamKind.map(): MatrixRtcStreamKind = when (this) {
    FfiStreamKind.MICROPHONE -> MatrixRtcStreamKind.MICROPHONE
    FfiStreamKind.CAMERA -> MatrixRtcStreamKind.CAMERA
    FfiStreamKind.SCREEN_SHARE -> MatrixRtcStreamKind.SCREEN_SHARE
    FfiStreamKind.SCREEN_SHARE_AUDIO -> MatrixRtcStreamKind.SCREEN_SHARE_AUDIO
    FfiStreamKind.DATA -> MatrixRtcStreamKind.DATA
}

internal fun MatrixRtcStreamKind.map(): FfiStreamKind = when (this) {
    MatrixRtcStreamKind.MICROPHONE -> FfiStreamKind.MICROPHONE
    MatrixRtcStreamKind.CAMERA -> FfiStreamKind.CAMERA
    MatrixRtcStreamKind.SCREEN_SHARE -> FfiStreamKind.SCREEN_SHARE
    MatrixRtcStreamKind.SCREEN_SHARE_AUDIO -> FfiStreamKind.SCREEN_SHARE_AUDIO
    MatrixRtcStreamKind.DATA -> FfiStreamKind.DATA
}

internal fun FfiTileKind.map(): MatrixRtcTileKind = when (this) {
    FfiTileKind.PERSON -> MatrixRtcTileKind.PERSON
    FfiTileKind.SCREEN_SHARE -> MatrixRtcTileKind.SCREEN_SHARE
}

internal fun MatrixRtcTileKind.map(): FfiTileKind = when (this) {
    MatrixRtcTileKind.PERSON -> FfiTileKind.PERSON
    MatrixRtcTileKind.SCREEN_SHARE -> FfiTileKind.SCREEN_SHARE
}

internal fun FfiTileId.map() = MatrixRtcTileId(memberId = memberId, kind = kind.map())

internal fun MatrixRtcTileId.map() = FfiTileId(memberId = memberId, kind = kind.map())

/** The FFI's `(offset, len)` for a window's rank range; an empty range is a zero-length window at zero. */
internal fun MatrixRtcDetailWindow.rankRange(): Pair<UInt, UInt> =
    if (ranks.isEmpty()) 0u to 0u else ranks.first.toUInt() to (ranks.last - ranks.first + 1).toUInt()

internal fun FfiTileRef.map() = MatrixRtcTileRef(id = id.map(), userId = UserId(userId), isHero = hero)

internal fun FfiCallTile.map() = MatrixRtcTile(
    id = MatrixRtcTileId(memberId = memberId, kind = kind.map()),
    userId = UserId(userId),
    deviceId = deviceId,
    isHero = hero,
    hasVideo = hasVideo,
    isMicrophoneMuted = microphoneMuted,
    isSpeaking = speaking,
    handRaisedAtMs = handRaisedAtMs?.toLong(),
    handRaisedPosition = handRaisedPosition?.toInt(),
    connection = connection.map(),
    unableToDecrypt = unableToDecrypt?.map(),
)

internal fun FfiInterruptions.map() = MatrixRtcInterruptions(
    signalling = signalling.map(),
    isOwnMembershipLapsed = ownMembershipLapsed,
    ownMedia = ownMedia.map(),
    membersReconnecting = membersReconnecting,
    membersUnreachable = membersUnreachable,
    unableToDecrypt = unableToDecrypt,
    unmappable = unmappable,
)

internal fun FfiSignallingHealth.map(): MatrixRtcSignallingHealth = when (this) {
    is FfiSignallingHealth.Reachable -> MatrixRtcSignallingHealth.Reachable
    is FfiSignallingHealth.Unreachable -> MatrixRtcSignallingHealth.Unreachable(sinceMs.toLong())
}

internal fun FfiConnectionState.map(): MatrixRtcConnectionState = when (this) {
    FfiConnectionState.CONNECTED -> MatrixRtcConnectionState.CONNECTED
    FfiConnectionState.CONNECTING -> MatrixRtcConnectionState.CONNECTING
    FfiConnectionState.RECONNECTING -> MatrixRtcConnectionState.RECONNECTING
    FfiConnectionState.UNREACHABLE -> MatrixRtcConnectionState.UNREACHABLE
}

internal fun FfiUnableToDecrypt.map(): MatrixRtcUnableToDecrypt = when (this) {
    FfiUnableToDecrypt.MISSING_KEY -> MatrixRtcUnableToDecrypt.MISSING_KEY
    FfiUnableToDecrypt.BAD_KEY -> MatrixRtcUnableToDecrypt.BAD_KEY
    FfiUnableToDecrypt.NOT_ENCRYPTED -> MatrixRtcUnableToDecrypt.NOT_ENCRYPTED
}

/**
 * Detail is a subsequence of the order, joined by identity: the core only sends full records for the
 * declared window, so the two lists line up by index only while that window covers everything.
 */
internal fun FfiTileRoster.map() = MatrixRtcTileRoster(
    order = order.map { it.map() },
    detail = detail.associate { tile -> tile.map().let { it.id to it } },
)

internal fun FfiLocalState.map() = MatrixRtcLocalState(
    tile = tile.map(),
    isScreenSharing = isScreenSharing,
)

internal fun MatrixRtcStreamRef.map() = FfiStreamRef(memberId = memberId, kind = kind.map())

/** The stream asked about and its counters; null counters mean no RTCP report yet, not zero. */
internal fun FfiStreamStats.map(): Pair<MatrixRtcStreamRef, MatrixRtcReceiveStats?> =
    MatrixRtcStreamRef(memberId, kind.map()) to stats?.map()

internal fun FfiCallStatus.map(): MatrixRtcCallStatus = when (this) {
    is FfiCallStatus.Connecting -> MatrixRtcCallStatus.Connecting
    is FfiCallStatus.Calling -> MatrixRtcCallStatus.Calling(callee = UserId(callee), ring = ring.map(), media = media.map())
    is FfiCallStatus.Connected -> MatrixRtcCallStatus.Connected(sinceMs = sinceMs.toLong(), degraded = degraded)
    is FfiCallStatus.Ended -> MatrixRtcCallStatus.Ended(reason.map())
}

private fun FfiRingPhase.map(): MatrixRtcRingPhase = when (this) {
    is FfiRingPhase.Sending -> MatrixRtcRingPhase.Sending
    is FfiRingPhase.Ringing -> MatrixRtcRingPhase.Ringing(sentAtMs = sentAtMs.toLong(), lifetimeMs = lifetimeMs.toLong())
}

private fun FfiMediaPhase.map(): MatrixRtcMediaPhase = when (this) {
    is FfiMediaPhase.Connecting -> MatrixRtcMediaPhase.Connecting
    is FfiMediaPhase.Connected -> MatrixRtcMediaPhase.Connected(degraded)
}

private fun FfiCallEnd.map(): MatrixRtcCallEnd = when (this) {
    is FfiCallEnd.Left -> MatrixRtcCallEnd.Left
    is FfiCallEnd.Declined -> MatrixRtcCallEnd.Declined
    is FfiCallEnd.Unanswered -> MatrixRtcCallEnd.Unanswered
    is FfiCallEnd.RingNotSent -> MatrixRtcCallEnd.RingNotSent
    is FfiCallEnd.OthersLeft -> MatrixRtcCallEnd.OthersLeft
    is FfiCallEnd.Empty -> MatrixRtcCallEnd.Empty
    is FfiCallEnd.SlotClosed -> MatrixRtcCallEnd.SlotClosed
    is FfiCallEnd.ConnectionClosed -> MatrixRtcCallEnd.ConnectionClosed(reason.map(), message)
}

private fun FfiConnectionLossReason.map(): MatrixRtcConnectionLossReason = when (this) {
    FfiConnectionLossReason.BUDGET -> MatrixRtcConnectionLossReason.BUDGET
    FfiConnectionLossReason.REFUSED -> MatrixRtcConnectionLossReason.REFUSED
    FfiConnectionLossReason.NEVER_CONNECTED -> MatrixRtcConnectionLossReason.NEVER_CONNECTED
}
