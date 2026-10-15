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

    @Test
    fun `the ringback and the end sounds are web's`() {
        assertThat(sha256("element_call_ringtone.mp3")).isEqualTo("f4528c08f5e362b9c5b3693a3349bd9224d32c586953e1d180e90c5414d626c4")
        assertThat(sha256("element_call_call_declined.mp3")).isEqualTo("bb8d93337c6d8a391fbc7750ce5fb955413ba084183a76c22aba5016f8d0e06b")
        assertThat(sha256("element_call_call_timeout.mp3")).isEqualTo("c0aed0c09a85d580824847988e6868121c1329997cf8a3df88a42895adbf3ddd")
    }

    private fun sha256(name: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(File("src/main/res/raw/$name").readBytes())
            .joinToString("") { "%02x".format(it) }
}
