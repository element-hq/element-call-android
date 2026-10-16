/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample.matrix

import org.matrix.rtc.FfiEventEncryption
import org.matrix.rustcomponents.sdk.EventEncryptionInfo
import org.matrix.rustcomponents.sdk.ShieldState
import uniffi.matrix_sdk_ui.TimelineEventShieldStateCode

/** How the SDK says an event arrived; no encryption info is cleartext. */
internal fun EventEncryptionInfo?.toFfi(): FfiEventEncryption = when (this) {
    null -> FfiEventEncryption(encrypted = false, senderDeviceId = null, senderCrossSigned = null)
    else -> FfiEventEncryption(encrypted = true, senderDeviceId = senderDeviceId, senderCrossSigned = shieldState.isSenderCrossSigned())
}

/**
 * MSC4153 asks whether the sending device is cross-signed, not whether we trust its owner, so an
 * unverified identity still counts. The rule of the core's own SDK backend, read off the lax shield:
 * a device that is unsigned, unknown or not attributable is not cross-signed.
 */
internal fun ShieldState.isSenderCrossSigned(): Boolean = when (this) {
    ShieldState.None -> true
    is ShieldState.Grey -> code !in NOT_CROSS_SIGNED
    is ShieldState.Red -> code !in NOT_CROSS_SIGNED
}

private val NOT_CROSS_SIGNED = setOf(
    TimelineEventShieldStateCode.UNSIGNED_DEVICE,
    TimelineEventShieldStateCode.UNKNOWN_DEVICE,
    TimelineEventShieldStateCode.AUTHENTICITY_NOT_GUARANTEED,
    TimelineEventShieldStateCode.MISMATCHED_SENDER,
)
