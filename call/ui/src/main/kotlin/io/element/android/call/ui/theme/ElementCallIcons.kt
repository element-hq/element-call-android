/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import io.element.android.call.ui.R

/**
 * The icons the call composables draw, named after what they mean rather than what they look like.
 *
 * The defaults are Compound's glyphs, bundled as vector drawables (compound-design-tokens, Apache-2.0),
 * except Bluetooth, which Compound has no glyph for and Material does. Element X provides its own
 * instance from `CompoundIcons` so the two never drift.
 */
@Immutable
data class ElementCallIcons(
    val microphoneOn: ImageVector,
    val microphoneOff: ImageVector,
    val cameraOn: ImageVector,
    val cameraOff: ImageVector,
    val switchCamera: ImageVector,
    val endCall: ImageVector,
    /** The share-screen control while nothing is shared. */
    val shareScreen: ImageVector,
    /** The share-screen control while sharing, the banner and a shared screen's name pill. */
    val shareScreenActive: ImageVector,
    /** Audio out of the loudspeaker. */
    val speaker: ImageVector,
    /** Audio out of the earpiece: read as "loudspeaker off". */
    val earpiece: ImageVector,
    val headphones: ImageVector,
    val bluetooth: ImageVector,
    /** The way out of the full-screen call, into the minimized bar. */
    val minimize: ImageVector,
    /** The tick beside the selected audio device. */
    val selected: ImageVector,
    /** The member-count pill. */
    val participants: ImageVector,
) {
    companion object {
        /** The bundled defaults. Resolved in composition, as vector resources are. */
        @Composable
        fun default(): ElementCallIcons = ElementCallIcons(
            microphoneOn = ImageVector.vectorResource(R.drawable.ic_element_call_mic_on_solid),
            microphoneOff = ImageVector.vectorResource(R.drawable.ic_element_call_mic_off_solid),
            cameraOn = ImageVector.vectorResource(R.drawable.ic_element_call_video_call_solid),
            cameraOff = ImageVector.vectorResource(R.drawable.ic_element_call_video_call_off_solid),
            switchCamera = ImageVector.vectorResource(R.drawable.ic_element_call_switch_camera_solid),
            endCall = ImageVector.vectorResource(R.drawable.ic_element_call_end_call),
            shareScreen = ImageVector.vectorResource(R.drawable.ic_element_call_share_screen),
            shareScreenActive = ImageVector.vectorResource(R.drawable.ic_element_call_share_screen_solid),
            speaker = ImageVector.vectorResource(R.drawable.ic_element_call_volume_on_solid),
            earpiece = ImageVector.vectorResource(R.drawable.ic_element_call_volume_off_solid),
            headphones = ImageVector.vectorResource(R.drawable.ic_element_call_headphones_solid),
            bluetooth = Icons.Rounded.Bluetooth,
            minimize = ImageVector.vectorResource(R.drawable.ic_element_call_collapse),
            selected = ImageVector.vectorResource(R.drawable.ic_element_call_check),
            participants = ImageVector.vectorResource(R.drawable.ic_element_call_user_profile),
        )
    }
}
