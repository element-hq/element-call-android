/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.MatrixRtcConnectionState
import io.element.android.call.api.rtc.MatrixRtcUnableToDecrypt
import org.junit.Test

class CallTileMediaStateTest {
    @Test
    fun `a connected tile with nothing to decrypt is live`() {
        assertThat(CallTileMediaState.of(MatrixRtcConnectionState.CONNECTED, null, isMissingKeys = false)).isEqualTo(CallTileMediaState.LIVE)
    }

    @Test
    fun `a lost connection wins over a decryption failure`() {
        assertThat(CallTileMediaState.of(MatrixRtcConnectionState.UNREACHABLE, MatrixRtcUnableToDecrypt.MISSING_KEY, isMissingKeys = true))
            .isEqualTo(CallTileMediaState.RECONNECTING)
        assertThat(CallTileMediaState.of(MatrixRtcConnectionState.CONNECTING, null, isMissingKeys = false)).isEqualTo(CallTileMediaState.CONNECTING)
    }

    @Test
    fun `a missing key waits, then says the keys are missing`() {
        assertThat(CallTileMediaState.of(MatrixRtcConnectionState.CONNECTED, MatrixRtcUnableToDecrypt.BAD_KEY, isMissingKeys = false))
            .isEqualTo(CallTileMediaState.UNDECRYPTABLE)
        assertThat(CallTileMediaState.of(MatrixRtcConnectionState.CONNECTED, MatrixRtcUnableToDecrypt.MISSING_KEY, isMissingKeys = true))
            .isEqualTo(CallTileMediaState.KEYS_MISSING)
    }

    @Test
    fun `media sent in clear says so at once`() {
        assertThat(CallTileMediaState.of(MatrixRtcConnectionState.CONNECTED, MatrixRtcUnableToDecrypt.NOT_ENCRYPTED, isMissingKeys = false))
            .isEqualTo(CallTileMediaState.NOT_ENCRYPTED)
    }
}
