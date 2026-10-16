/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample.matrix

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.matrix.rtc.FfiEventEncryption
import org.matrix.rustcomponents.sdk.EventEncryptionInfo
import org.matrix.rustcomponents.sdk.ShieldState
import uniffi.matrix_sdk_ui.TimelineEventShieldStateCode

class SdkEncryptionTest {
    @Test
    fun `no encryption info is cleartext, with nothing said about a sender`() {
        val info: EventEncryptionInfo? = null

        assertThat(info.toFfi()).isEqualTo(FfiEventEncryption(encrypted = false, senderDeviceId = null, senderCrossSigned = null))
    }

    @Test
    fun `an encrypted event carries the sending device`() {
        assertThat(anEncryptionInfo(ALICE, ShieldState.None).toFfi())
            .isEqualTo(FfiEventEncryption(encrypted = true, senderDeviceId = A_SENDER_DEVICE, senderCrossSigned = true))
    }

    @Test
    fun `an unsigned, unknown or unattributable device is not cross-signed`() {
        listOf(
            ShieldState.Red(TimelineEventShieldStateCode.UNSIGNED_DEVICE),
            ShieldState.Grey(TimelineEventShieldStateCode.UNKNOWN_DEVICE),
            ShieldState.Grey(TimelineEventShieldStateCode.AUTHENTICITY_NOT_GUARANTEED),
            ShieldState.Red(TimelineEventShieldStateCode.MISMATCHED_SENDER),
        ).forEach { shield ->
            assertThat(shield.isSenderCrossSigned()).isFalse()
        }
    }

    @Test
    fun `an unverified or violating identity still signs its own devices`() {
        // MSC4153 asks about the device, not about trusting its owner.
        assertThat(ShieldState.None.isSenderCrossSigned()).isTrue()
        assertThat(ShieldState.Red(TimelineEventShieldStateCode.VERIFICATION_VIOLATION).isSenderCrossSigned()).isTrue()
    }

    private companion object {
        const val ALICE = "@alice:example.org"
    }
}
