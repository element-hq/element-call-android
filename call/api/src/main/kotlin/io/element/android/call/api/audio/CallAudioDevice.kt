/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.audio

/**
 * Somewhere call audio can come out of.
 *
 * [productName] is the hardware's own name and [type] is what it is. They are kept apart rather than
 * pre-formatted into a label because only the UI layer can localise, and the WebView path's
 * equivalent - which builds an English string in the audio layer and carries a "maybe translate
 * these?" note - is exactly what that costs.
 */
data class CallAudioDevice(
    /** The platform's id for this device. Stable only while the device stays connected. */
    val id: Int,
    val type: CallAudioDeviceType,
    /** What the hardware calls itself. Null, or not worth showing, for the phone's own speaker and earpiece. */
    val productName: String?,
)

enum class CallAudioDeviceType {
    BLUETOOTH,
    USB_HEADSET,
    USB_DEVICE,
    USB_ACCESSORY,
    WIRED_HEADSET,
    WIRED_HEADPHONES,
    EARPIECE,
    SPEAKER;

    /** Whether the device is part of the phone, and so has no name worth showing beside its type. */
    val isBuiltIn: Boolean get() = this == EARPIECE || this == SPEAKER
}
