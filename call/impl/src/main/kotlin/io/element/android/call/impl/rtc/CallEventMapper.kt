/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.api.rtc.MatrixRtcCallEvent
import io.element.android.call.api.rtc.MatrixRtcEndReason
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionDiagnostic
import io.element.android.call.api.rtc.MatrixRtcFrameEncryptionState
import io.element.android.call.api.rtc.MatrixRtcKeyRejection
import io.element.android.call.api.rtc.MatrixRtcMembership
import io.element.android.call.api.rtc.MatrixRtcParticipant
import io.element.android.call.api.rtc.MatrixRtcReceiveStats
import io.element.android.call.api.rtc.MatrixRtcSpeakingMember
import io.element.android.call.api.rtc.MatrixRtcStreamKind
import io.element.android.call.api.rtc.MatrixRtcStreamState
import uniffi.matrix_rtc_ffi.FfiCallEvent
import uniffi.matrix_rtc_ffi.FfiEndedReason
import uniffi.matrix_rtc_ffi.FfiFrameEncryptionDiagnostic
import uniffi.matrix_rtc_ffi.FfiFrameEncryptionState
import uniffi.matrix_rtc_ffi.FfiKeyRejection
import uniffi.matrix_rtc_ffi.FfiParticipant
import uniffi.matrix_rtc_ffi.FfiReceiveStats
import uniffi.matrix_rtc_ffi.FfiSpeakingMember
import uniffi.matrix_rtc_ffi.FfiStreamKind
import uniffi.matrix_rtc_ffi.FfiStreamState
import uniffi.matrix_rtc_ffi.JoinedMembership

internal fun FfiCallEvent.map(): MatrixRtcCallEvent = when (this) {
    is FfiCallEvent.ParticipantJoined -> MatrixRtcCallEvent.ParticipantJoined(memberId, UserId(userId))
    is FfiCallEvent.ParticipantLeft -> MatrixRtcCallEvent.ParticipantLeft(memberId)
    is FfiCallEvent.StreamStarted -> MatrixRtcCallEvent.StreamStarted(memberId, kind.map())
    is FfiCallEvent.StreamStopped -> MatrixRtcCallEvent.StreamStopped(memberId, kind.map())
    is FfiCallEvent.StreamMuted -> MatrixRtcCallEvent.StreamMuted(memberId, kind.map())
    is FfiCallEvent.StreamUnmuted -> MatrixRtcCallEvent.StreamUnmuted(memberId, kind.map())
    is FfiCallEvent.ActiveSpeakers -> MatrixRtcCallEvent.ActiveSpeakers(speakers.map { it.map() })
    is FfiCallEvent.MediaConnectionState -> MatrixRtcCallEvent.MediaConnectionDegraded(degraded)
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
    is FfiCallEvent.Ended -> MatrixRtcCallEvent.Ended(reason.map())
}

internal fun FfiSpeakingMember.map() = MatrixRtcSpeakingMember(
    memberId = memberId,
    level = level,
)

internal fun FfiKeyRejection.map(): MatrixRtcKeyRejection = when (this) {
    is FfiKeyRejection.Cleartext -> MatrixRtcKeyRejection.Cleartext
    is FfiKeyRejection.NotCrossSigned -> MatrixRtcKeyRejection.NotCrossSigned
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

internal fun FfiEndedReason.map(): MatrixRtcEndReason = when (this) {
    is FfiEndedReason.Left -> MatrixRtcEndReason.Left
    is FfiEndedReason.ConnectionClosed -> MatrixRtcEndReason.ConnectionClosed(message)
}

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

internal fun FfiParticipant.map() = MatrixRtcParticipant(
    memberId = memberId,
    userId = UserId(userId),
    deviceId = deviceId,
    isLocal = isLocal,
    isReachable = reachable,
    streams = streams.map { it.map() },
)

internal fun FfiStreamState.map() = MatrixRtcStreamState(
    kind = kind.map(),
    isMuted = muted,
)
