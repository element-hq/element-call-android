/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.call.api.audio.CallAudioDevice
import io.element.android.call.api.audio.CallAudioDeviceType
import io.element.android.call.ui.preview.ElementCallPreview
import io.element.android.call.ui.preview.PreviewsDayNight
import io.element.android.call.ui.theme.ElementCallTheme
import kotlinx.collections.immutable.ImmutableList

/**
 * Everywhere the call could come out of, with the current route ticked.
 *
 * The WebView path cannot show this: there, Element Call owns the picker inside the page and Android
 * only enumerates devices for it and applies what it picks back. Owning both halves is what makes a
 * headset paired mid-call simply appear in this list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioDevicePicker(
    devices: ImmutableList<CallAudioDevice>,
    selectedDevice: CallAudioDevice?,
    onSelect: (CallAudioDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
        // The one surface here that Material would otherwise paint from the *host's* theme. Everything
        // drawn on it uses this component's palette, so a host with a light theme got the call's
        // near-white text on a white sheet. The sample never showed it: its MaterialTheme is dark.
        containerColor = ElementCallTheme.colors.bgSubtlePrimary,
        contentColor = ElementCallTheme.colors.textPrimary,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = stringResource(R.string.element_call_audio_output_title),
                style = ElementCallTheme.typography.bodyLgMedium,
                color = ElementCallTheme.colors.textPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            devices.forEach { device ->
                AudioDeviceRow(
                    device = device,
                    isSelected = device.id == selectedDevice?.id,
                    onClick = {
                        onSelect(device)
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun AudioDeviceRow(
    device: CallAudioDevice,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = device.type.icon(),
            contentDescription = null,
            tint = ElementCallTheme.colors.iconSecondary,
        )
        Text(
            text = device.label(),
            style = ElementCallTheme.typography.bodyMdRegular,
            color = ElementCallTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Icon(
                imageVector = ElementCallTheme.icons.selected,
                contentDescription = stringResource(R.string.element_call_a11y_selected),
                tint = ElementCallTheme.colors.iconAccent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * What to call this device.
 *
 * Built here rather than in the audio layer so the type names can be translated. External hardware
 * gets its own name where it has one - "Bluetooth" alone is useless when two headsets are paired.
 */
@Composable
internal fun CallAudioDevice.label(): String {
    val typeName = stringResource(type.labelRes())
    return if (type.isBuiltIn || productName.isNullOrBlank()) typeName else productName!!
}

private fun CallAudioDeviceType.labelRes(): Int = when (this) {
    CallAudioDeviceType.BLUETOOTH -> R.string.element_call_audio_device_bluetooth
    CallAudioDeviceType.USB_HEADSET,
    CallAudioDeviceType.USB_DEVICE,
    CallAudioDeviceType.USB_ACCESSORY -> R.string.element_call_audio_device_usb
    CallAudioDeviceType.WIRED_HEADSET,
    CallAudioDeviceType.WIRED_HEADPHONES -> R.string.element_call_audio_device_headphones
    CallAudioDeviceType.EARPIECE -> R.string.element_call_audio_device_earpiece
    CallAudioDeviceType.SPEAKER -> R.string.element_call_audio_device_speaker
}

/**
 * Read as "how loud is this route": speaker is the volume icon, earpiece the struck-through one, so
 * the toolbar button says at a glance whether the phone is on loudspeaker. A handset glyph for the
 * earpiece read as a second call button next to hang-up.
 *
 * Compound has no Bluetooth glyph, so that one comes from the Material set. Wired and USB headsets
 * share the headphones icon and are told apart by their label.
 */
@Composable
internal fun CallAudioDeviceType.icon() = when (this) {
    CallAudioDeviceType.BLUETOOTH -> ElementCallTheme.icons.bluetooth
    CallAudioDeviceType.USB_HEADSET,
    CallAudioDeviceType.USB_DEVICE,
    CallAudioDeviceType.USB_ACCESSORY,
    CallAudioDeviceType.WIRED_HEADSET,
    CallAudioDeviceType.WIRED_HEADPHONES -> ElementCallTheme.icons.headphones
    CallAudioDeviceType.EARPIECE -> ElementCallTheme.icons.earpiece
    CallAudioDeviceType.SPEAKER -> ElementCallTheme.icons.speaker
}

// The sheet itself cannot be previewed - a ModalBottomSheet needs a real window - so the rows it is
// made of are previewed instead, which is where all the per-device rendering actually lives.
@PreviewsDayNight
@Composable
internal fun AudioDeviceRowPreview(@PreviewParameter(CallAudioDevicePreviewParam::class) device: CallAudioDevice) = ElementCallPreview {
    AudioDeviceRow(device = device, isSelected = device.type == CallAudioDeviceType.BLUETOOTH, onClick = {})
}

open class CallAudioDevicePreviewParam : PreviewParameterProvider<CallAudioDevice> {
    override val values: Sequence<CallAudioDevice>
        get() = sequenceOf(
            CallAudioDevice(id = 1, type = CallAudioDeviceType.EARPIECE, productName = null),
            CallAudioDevice(id = 2, type = CallAudioDeviceType.SPEAKER, productName = null),
            // Named hardware: the type alone is useless when two headsets are paired.
            CallAudioDevice(id = 3, type = CallAudioDeviceType.BLUETOOTH, productName = "WH-1000XM4"),
            CallAudioDevice(id = 4, type = CallAudioDeviceType.WIRED_HEADSET, productName = "Wired headset"),
        )
}
