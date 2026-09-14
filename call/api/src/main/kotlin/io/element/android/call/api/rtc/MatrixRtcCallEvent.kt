/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

import io.element.android.call.api.rtc.id.UserId

/**
 * Something that happened on the media transport of a connected call.
 */
sealed interface MatrixRtcCallEvent {
    data class ParticipantJoined(val memberId: String, val userId: UserId) : MatrixRtcCallEvent

    data class ParticipantLeft(val memberId: String) : MatrixRtcCallEvent

    data class StreamStarted(val memberId: String, val kind: MatrixRtcStreamKind) : MatrixRtcCallEvent

    data class StreamStopped(val memberId: String, val kind: MatrixRtcStreamKind) : MatrixRtcCallEvent

    data class StreamMuted(val memberId: String, val kind: MatrixRtcStreamKind) : MatrixRtcCallEvent

    data class StreamUnmuted(val memberId: String, val kind: MatrixRtcStreamKind) : MatrixRtcCallEvent

    /**
     * Who the transport currently hears, and how loudly.
     *
     * The level comes from the RTP audio-level header, which survives frame encryption, so it says
     * something [MatrixRtcAudioLevel] cannot: it is measured at the sender rather than on the PCM
     * our own jitter buffer handed us.
     */
    data class ActiveSpeakers(val speakers: List<MatrixRtcSpeakingMember>) : MatrixRtcCallEvent

    /** The transport is still up but struggling. */
    data class MediaConnectionDegraded(val degraded: Boolean) : MatrixRtcCallEvent

    data class KeyImported(val memberId: String, val keyIndex: Int) : MatrixRtcCallEvent

    /**
     * A media key arrived and was refused.
     *
     * Distinct from a key that never arrived, which is the whole point of reporting it: both leave
     * the member at [MatrixRtcFrameEncryptionState.MISSING_KEY], but only one of them is a delivery
     * problem. [MatrixRtcKeyRejection.NotCrossSigned] in particular is a "verify this device"
     * prompt rather than a fault.
     */
    data class KeyDiscarded(
        val memberId: String,
        /** Null when the key was refused before its index could be read. */
        val keyIndex: Int?,
        /** Null when nothing attested who sent it, which is itself one of the reasons to refuse. */
        val senderUserId: UserId?,
        val senderDeviceId: String?,
        val reason: MatrixRtcKeyRejection,
    ) : MatrixRtcCallEvent

    /**
     * How frame encryption is faring for one member.
     *
     * Per member rather than per stream: the frame cryptor is keyed by transport identity, so a
     * failure cannot be attributed to one of their tracks.
     */
    data class FrameEncryption(
        val memberId: String,
        val state: MatrixRtcFrameEncryptionState,
        val diagnostic: MatrixRtcFrameEncryptionDiagnostic,
    ) : MatrixRtcCallEvent

    /** Someone is publishing to the transport that we cannot tie back to a MatrixRTC membership. */
    data class UnknownParticipant(val identity: String) : MatrixRtcCallEvent

    data class Ended(val reason: MatrixRtcEndReason) : MatrixRtcCallEvent
}

/** One member the transport currently hears, with [level] from 0f to 1f. */
data class MatrixRtcSpeakingMember(
    val memberId: String,
    val level: Float,
)

sealed interface MatrixRtcEndReason {
    data object Left : MatrixRtcEndReason

    data class ConnectionClosed(val message: String) : MatrixRtcEndReason
}

/**
 * Why a media key we received was thrown away.
 *
 * Typed rather than a message because the cases need different responses: a mismatch is someone
 * else's bug, [NotCrossSigned] and [UnverifiableDevice] are things the user can act on.
 */
sealed interface MatrixRtcKeyRejection {
    /** The key arrived unencrypted, so nothing vouches for who sent it. */
    data object Cleartext : MatrixRtcKeyRejection

    /** The sending device is not cross-signed by its owner. */
    data object NotCrossSigned : MatrixRtcKeyRejection

    /** The sending device's identity could not be established at all. */
    data object UnverifiableDevice : MatrixRtcKeyRejection

    data class RoomMismatch(val claimed: String) : MatrixRtcKeyRejection

    data class SenderMismatch(val expected: String, val actual: String) : MatrixRtcKeyRejection

    /** [actual] is null when the key arrived with no attested sending device at all. */
    data class DeviceMismatch(val expected: String, val actual: String?) : MatrixRtcKeyRejection
}

/**
 * What the frame cryptor's key ring held when it reported a [MatrixRtcFrameEncryptionState].
 *
 * This is what splits [MatrixRtcFrameEncryptionState.MISSING_KEY] into the two cases that need
 * different investigations: nothing ever arrived, versus frames carrying an index we have not been
 * given yet.
 */
sealed interface MatrixRtcFrameEncryptionDiagnostic {
    /** The state is not a failure, so there is nothing to explain. */
    data object NotApplicable : MatrixRtcFrameEncryptionDiagnostic

    /** No key is installed for this participant under any index. */
    data object NoKeyInstalled : MatrixRtcFrameEncryptionDiagnostic

    /** Keys are installed at these indices, so the frames carry a different one. */
    data class KeysInstalled(val keyIndices: List<Int>) : MatrixRtcFrameEncryptionDiagnostic
}

enum class MatrixRtcFrameEncryptionState {
    OK,

    /** We have no key for this member yet: their media arrives but cannot be decrypted. */
    MISSING_KEY,
    DECRYPTION_FAILED,

    /** Our own outgoing frames could not be encrypted, so nobody can hear us. */
    ENCRYPTION_FAILED,
    INTERNAL_ERROR,
}

enum class MatrixRtcStreamKind {
    MICROPHONE,
    CAMERA,
    SCREEN_SHARE,
    SCREEN_SHARE_AUDIO,
    DATA,
}
