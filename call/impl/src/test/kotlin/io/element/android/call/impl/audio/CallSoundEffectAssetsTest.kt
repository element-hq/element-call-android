/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Web's files, unchanged: the hashes in docs/sounds.md, which iOS quotes too. */
class CallSoundEffectAssetsTest {
    @Test
    fun `the join sound is web's`() {
        assertThat(sha256("element_call_join_call.mp3")).isEqualTo("2a5e12c8470f6602249577e6c783231d70e80784504bae419a299330bc5a53f8")
    }

    @Test
    fun `the leave sound is web's`() {
        assertThat(sha256("element_call_left_call.mp3")).isEqualTo("5f4c529262388c3cae87df5957e9607ac438cba2be08d1b84484adf73c137971")
    }

    @Test
    fun `the raised hand sound is web's`() {
        assertThat(sha256("element_call_raise_hand.mp3")).isEqualTo("0601948ad6e4937cc6bca126f0decb2218037f39f6f7dc99cee359efdb66212b")
    }

    private fun sha256(name: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(File("src/main/res/raw/$name").readBytes())
            .joinToString("") { "%02x".format(it) }
}
