/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test.audio

import io.element.android.call.api.audio.AudioFocus
import io.element.android.call.api.audio.AudioFocusRequester
import io.element.android.tests.testutils.lambda.lambdaError

class FakeAudioFocus(
    private val requestAudioFocusResult: (AudioFocusRequester, () -> Unit) -> Unit = { _, _ -> lambdaError() },
    private val releaseAudioFocusResult: () -> Unit = { lambdaError() },
) : AudioFocus {
    override fun requestAudioFocus(
        requester: AudioFocusRequester,
        onFocusLost: () -> Unit,
    ) {
        requestAudioFocusResult(requester, onFocusLost)
    }

    override fun releaseAudioFocus() {
        releaseAudioFocusResult()
    }
}
