/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.callnative.impl.NativeCallConnection
import io.element.android.libraries.audio.api.CallAudioDeviceType
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrixrtc.api.MatrixRtcAudioLevel
import io.element.android.libraries.matrixrtc.api.MatrixRtcFrameEncryptionState
import io.element.android.libraries.matrixrtc.api.MatrixRtcParticipant
import io.element.android.libraries.matrixrtc.api.MatrixRtcReceiveStats
import io.element.android.libraries.matrixrtc.api.MatrixRtcStreamKind
import io.element.android.libraries.matrixrtc.api.MatrixRtcVideoFrame
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt

@Composable
fun NativeCallView(
    state: NativeCallState,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Grouped so the outer SpaceBetween still sees three blocks: header, list, controls.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CallHeader(state)
                CallStatus(state)
            }
            ParticipantList(state, modifier = Modifier.weight(1f, fill = false))
            CallControls(state)
        }
    }
}

/**
 * The row above the call status, whose only job is getting out of the call without leaving it.
 *
 * Minimizing is what the WebView path has no answer to: there, leaving the call screen means leaving
 * the call, so the only exits it can offer are hang up and the system's own picture-in-picture.
 */
@Composable
private fun CallHeader(state: NativeCallState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { state.eventSink(NativeCallEvent.Minimize) }) {
            Icon(
                imageVector = CompoundIcons.Collapse(),
                contentDescription = stringResource(CommonStrings.a11y_minimize_call),
                tint = ElementTheme.colors.iconPrimary,
            )
        }
    }
}

@Composable
private fun CallStatus(state: NativeCallState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = state.connection.label(),
            style = ElementTheme.typography.fontHeadingMdBold,
            color = ElementTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            // Two different sources, labelled as such: the count is the core's membership projection,
            // and the fallback is what the media transport actually sees. Conflating them would hide
            // exactly the disagreement worth noticing when testing against another implementation.
            text = "${state.memberCount} in call",
            style = ElementTheme.typography.fontBodyMdRegular,
            color = ElementTheme.colors.textSecondary,
        )
        val failure = state.connection as? NativeCallConnection.Failed
        if (failure != null) {
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = failure.message,
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textCriticalPrimary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ParticipantList(state: NativeCallState, modifier: Modifier = Modifier) {
    Column(
        // Scrollable and weighted: stale memberships have already pushed this list to five in a
        // two-party call, and without this the hang-up button goes off the bottom of the screen.
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.participants.forEach { participant ->
            ParticipantCard(
                participant = participant,
                meter = state.audioLevels[participant.memberId],
                stats = state.receiveStats[participant.memberId],
                videoFrames = state.videoFrames[participant.memberId],
                // Only ever our own front camera. Mirroring a remote member would be wrong twice
                // over: it is not how they look to themselves, and any text in frame reads backwards.
                isVideoMirrored = participant.isLocal && state.isFrontCamera,
                // Distinguished from "not in the map": a member the core has never reported a state
                // for is not the same as one it reports as fine, and reading the two as one thing has
                // already cost us a diagnosis.
                encryption = state.frameEncryption[participant.memberId],
                hasEncryptionState = participant.memberId in state.frameEncryption,
                isActiveSpeaker = participant.memberId in state.activeSpeakerIds,
            )
        }
    }
}

/**
 * One member, with everything we know about their audio, boxed so it cannot be read as the next
 * member's.
 *
 * Every number here belongs to one member and several of them look alike across rows, so the card
 * boundary is doing real work rather than decoration: a frame count attributed to the wrong person
 * inverts the diagnosis. The member id is on the card for the same reason - it is what the logs and
 * the RTC core call this participant, and without it the screen cannot be lined up against a logcat.
 *
 * The wave is the level we measured on the PCM itself and the frame count is how much PCM there has
 * been - though for a remote member a jitter buffer with nothing to play still emits silence, so see
 * [MatrixRtcAudioLevel] before reading too much into a rising count. "Heard by SFU" is the
 * independent signal: the transport reads the RTP audio level, which survives encryption, so a
 * member the SFU hears while our wave stays flat is one we are failing to decode.
 *
 * The `e2ee` row is the per-member frame cryptor and nothing else. It is not a claim about the wire:
 * media always travels encrypted to the SFU, and this row says whether it is *also* encrypted end to
 * end, which is what decides whether the SFU could listen. "not reported yet" means the core has said
 * nothing about this member - which is not the same as fine, and not the same as unencrypted - so it is
 * deliberately distinct from a reported state and drawn in the neutral colour.
 *
 * The video tile sits above all of it when this member has one, so the picture and the numbers
 * describing it stay in the same box - the same reason the numbers are boxed per member at all.
 *
 * @param hasEncryptionState whether the core has reported any frame encryption state for this member.
 * @param videoFrames this member's video, or null when they have none to show.
 * @param isVideoMirrored whether [videoFrames] should be flipped horizontally, which is true only
 * for our own front camera.
 */
@Composable
private fun ParticipantCard(
    participant: MatrixRtcParticipant,
    meter: MatrixRtcAudioLevel?,
    stats: MatrixRtcReceiveStats?,
    encryption: MatrixRtcFrameEncryptionState?,
    hasEncryptionState: Boolean,
    isActiveSpeaker: Boolean,
    videoFrames: Flow<MatrixRtcVideoFrame>?,
    isVideoMirrored: Boolean,
) {
    // The roster for everyone, ourselves included: publishing raises a stream event against our own
    // member id and muting goes to the transport, so our own row is now built from the same source
    // as every other and there is nothing left to shadow. The frame count beside this says whether
    // it is working: "mic on" with a count stuck at zero is a track nothing feeds.
    val isMuted = participant.streams
        .firstOrNull { it.kind == MatrixRtcStreamKind.MICROPHONE }
        ?.isMuted

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = ElementTheme.colors.bgSubtleSecondary,
        // The border tracks who is speaking rather than adding a separate indicator: it is the one
        // per-member fact that changes second to second, and on the card edge it is readable without
        // having to find and parse the right line of small text.
        border = BorderStroke(
            width = 1.dp,
            color = if (isActiveSpeaker) ElementTheme.colors.borderSuccessSubtle else ElementTheme.colors.borderDisabled,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (videoFrames != null) {
                NativeCallVideoTile(frames = videoFrames, isMirrored = isVideoMirrored)
            }
            Text(
                text = if (participant.isLocal) "${participant.userId.value} (you)" else participant.userId.value,
                style = ElementTheme.typography.fontBodyMdMedium,
                color = ElementTheme.colors.textPrimary,
            )
            Text(
                text = buildString {
                    append(participant.memberId.take(SHORT_MEMBER_ID_LENGTH))
                    participant.deviceId?.let { append(" · $it") }
                    when (isMuted) {
                        true -> append(" · mic muted")
                        false -> append(" · mic on")
                        null -> append(" · no mic stream")
                    }
                    if (!participant.isReachable) append(" · unreachable")
                },
                style = ElementTheme.typography.fontBodyXsRegular,
                color = ElementTheme.colors.textSecondary,
            )
            AudioWaveform(
                level = meter?.level ?: 0f,
                modifier = Modifier.fillMaxWidth(),
                // Our own wave is metered before the mute check, so it keeps moving while we are muted -
                // which is the point, it is the only thing that says the capture loop is still alive. Dimmed
                // so a moving wave next to "mic muted" cannot be read as us still transmitting.
                color = if (isMuted == true) ElementTheme.colors.iconDisabled else ElementTheme.colors.iconAccentTertiary,
            )
            MetricLine(
                label = "audio",
                value = buildString {
                    append("${meter?.frameCount ?: 0} frames")
                    append(" · level ${"%.2f".format(meter?.level ?: 0f)}")
                    if (isActiveSpeaker) append(" · heard by SFU")
                },
            )
            // The device half of the picture, which the rtp line below cannot see: a loop running
            // short of real time drains the buffer it feeds, and every under-run is a crackle. Only
            // shown once measured, and coloured only when it is actually bad, so a healthy call
            // does not read as a warning.
            val realtimeRatio = meter?.realtimeRatio
            val underruns = meter?.underrunCount
            if (realtimeRatio != null || (underruns != null && underruns > 0)) {
                MetricLine(
                    label = "device",
                    value = buildString {
                        append(if (realtimeRatio == null) "realtime unknown" else "realtime ${"%.2f".format(realtimeRatio)}x")
                        if (underruns != null) append(" · $underruns under-runs")
                    },
                    color = if (isPlaybackStarving(realtimeRatio, underruns)) {
                        ElementTheme.colors.textCriticalPrimary
                    } else {
                        ElementTheme.colors.textSecondary
                    },
                )
            }
            MetricLine(
                label = "rtp",
                value = if (stats == null) {
                    "no receive stats yet"
                } else {
                    buildString {
                        append("${stats.packetsReceived} pkts · ${stats.packetsLost} lost")
                        val invented = stats.concealedFraction
                        append(" · ${if (invented == null) "unknown" else "${(invented * 100).roundToInt()}%"} invented")
                    }
                },
            )
            MetricLine(
                // "e2ee" rather than "frames": this is the per-member frame cryptor, and the previous label
                // invited the reading that a member with no state was sending in the clear. Nothing here is
                // ever in the clear - the transport encrypts to the SFU either way - this row is only about
                // whether the media is *also* encrypted end to end, which is what keeps the SFU out.
                label = "e2ee",
                value = if (hasEncryptionState) encryption.label() else "not reported yet",
                color = when {
                    !hasEncryptionState -> ElementTheme.colors.textSecondary
                    encryption == MatrixRtcFrameEncryptionState.OK -> ElementTheme.colors.textSuccessPrimary
                    else -> ElementTheme.colors.textCriticalPrimary
                },
            )
        }
    }
}

/**
 * One labelled fact, with the labels aligned down the card so the eye can find the same row on every
 * member without reading the values.
 */
@Composable
private fun MetricLine(
    label: String,
    value: String,
    color: Color = ElementTheme.colors.textSecondary,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            modifier = Modifier.width(44.dp),
            style = ElementTheme.typography.fontBodyXsRegular,
            color = ElementTheme.colors.textDisabled,
        )
        Text(
            text = value,
            style = ElementTheme.typography.fontBodyXsRegular,
            color = color,
        )
    }
}

/** Enough of the member id to match a logcat line, which quotes the same prefix. */
private const val SHORT_MEMBER_ID_LENGTH = 8

private fun MatrixRtcFrameEncryptionState?.label(): String = when (this) {
    MatrixRtcFrameEncryptionState.OK -> "encrypted end to end"
    MatrixRtcFrameEncryptionState.MISSING_KEY -> "no key for this member"
    MatrixRtcFrameEncryptionState.DECRYPTION_FAILED -> "decryption failed"
    MatrixRtcFrameEncryptionState.ENCRYPTION_FAILED -> "our encryption failed"
    MatrixRtcFrameEncryptionState.INTERNAL_ERROR -> "encryption error"
    null -> "not reported yet"
}

@Composable
private fun CallControls(state: NativeCallState) {
    Row(
        // Shared out across the width rather than a fixed gap between buttons. A fixed 32dp stopped
        // fitting the moment the camera and flip buttons arrived: the row ran off the screen, and what
        // it cost was the hang-up icon - the button's red circle still drew, so it looked like a
        // deliberate blank rather than something clipped.
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { state.eventSink(NativeCallEvent.ToggleMicrophoneMuted) },
            enabled = state.isMicrophonePermissionGranted,
        ) {
            Icon(
                imageVector = if (state.isMicrophoneMuted) {
                    CompoundIcons.MicOffSolid()
                } else {
                    CompoundIcons.MicOnSolid()
                },
                contentDescription = if (state.isMicrophoneMuted) "Unmute microphone" else "Mute microphone",
                tint = ElementTheme.colors.iconPrimary,
            )
        }
        // Enabled whatever the permission state: tapping this is what asks for the camera, so
        // disabling it until the permission exists would leave no way to grant it.
        IconButton(
            onClick = { state.eventSink(NativeCallEvent.ToggleCamera) },
        ) {
            Icon(
                imageVector = if (state.isCameraEnabled) {
                    CompoundIcons.VideoCallSolid()
                } else {
                    CompoundIcons.VideoCallOffSolid()
                },
                contentDescription = if (state.isCameraEnabled) "Turn the camera off" else "Turn the camera on",
                tint = if (state.isCameraEnabled) {
                    ElementTheme.colors.iconAccentTertiary
                } else {
                    ElementTheme.colors.iconSecondary
                },
            )
        }
        // Only while something is being captured - there is no camera to swap otherwise, and a
        // button that silently does nothing is worse than one that is visibly unavailable.
        IconButton(
            onClick = { state.eventSink(NativeCallEvent.SwitchCamera) },
            enabled = state.isCameraEnabled,
        ) {
            Icon(
                imageVector = CompoundIcons.SwitchCameraSolid(),
                contentDescription = "Switch camera",
                tint = ElementTheme.colors.iconSecondary,
            )
        }
        // The icon is whatever audio is currently coming out of, so the route is readable without
        // opening anything - which is the question people actually have mid-call. Opens the picker
        // rather than toggling: with a headset connected there are three or more answers, and a
        // toggle can only ever express two.
        var isAudioPickerVisible by remember { mutableStateOf(false) }
        IconButton(
            onClick = { isAudioPickerVisible = true },
            enabled = state.audioDevices.isNotEmpty(),
        ) {
            Icon(
                imageVector = (state.selectedAudioDevice?.type ?: CallAudioDeviceType.EARPIECE).icon(),
                contentDescription = stringResource(CommonStrings.screen_call_audio_output_title),
                tint = ElementTheme.colors.iconSecondary,
            )
        }
        if (isAudioPickerVisible) {
            AudioDevicePicker(
                devices = state.audioDevices,
                selectedDevice = state.selectedAudioDevice,
                onSelect = { state.eventSink(NativeCallEvent.SelectAudioDevice(it)) },
                onDismiss = { isAudioPickerVisible = false },
            )
        }
        // Publishing a known tone instead of the microphone: on an emulator, or anywhere the mic is
        // suspect, this is what tells capture apart from everything downstream of it.
        IconButton(
            onClick = { state.eventSink(NativeCallEvent.ToggleAudioTestTone) },
        ) {
            Icon(
                imageVector = CompoundIcons.Audio(),
                contentDescription = if (state.isAudioTestToneEnabled) "Stop the test tone" else "Publish a test tone",
                tint = if (state.isAudioTestToneEnabled) {
                    ElementTheme.colors.iconAccentTertiary
                } else {
                    ElementTheme.colors.iconSecondary
                },
            )
        }
        IconButton(
            onClick = { state.eventSink(NativeCallEvent.HangUp) },
            modifier = Modifier
                .clip(CircleShape)
                .background(ElementTheme.colors.bgCriticalPrimary),
        ) {
            Icon(
                imageVector = CompoundIcons.EndCall(),
                contentDescription = "Hang up",
                tint = ElementTheme.colors.iconOnSolidPrimary,
            )
        }
    }
}

/**
 * Whether the device side is the thing to look at, rather than the network.
 *
 * The threshold is deliberately not 1.0: a loop that is keeping up still wanders either side of real
 * time between two samples, and the fault this was built for was nowhere near the margin - playback
 * sat at 0.84-0.92x while it crackled, against 1.00x when it did not. Any under-run at all counts,
 * because the platform only counts a buffer it actually wanted and did not get.
 */
private fun isPlaybackStarving(realtimeRatio: Float?, underruns: Int?): Boolean =
    (realtimeRatio != null && realtimeRatio < STARVING_REALTIME_RATIO) || (underruns != null && underruns > 0)

private const val STARVING_REALTIME_RATIO = 0.97f

internal fun NativeCallConnection.label(): String = when (this) {
    NativeCallConnection.RequestingPermission -> "Waiting for microphone"
    NativeCallConnection.Joining -> "Joining call"
    NativeCallConnection.ConnectingMedia -> "Connecting media"
    NativeCallConnection.Connected -> "Connected"
    NativeCallConnection.Degraded -> "Connection unstable"
    is NativeCallConnection.Failed -> "Call failed"
    NativeCallConnection.Ended -> "Call ended"
}

@PreviewsDayNight
@Composable
internal fun NativeCallViewPreview(
    @androidx.compose.ui.tooling.preview.PreviewParameter(NativeCallStatePreviewParam::class) state: NativeCallState,
) = ElementPreview {
    NativeCallView(state = state)
}
