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

    @Test
    fun `the reaction sounds are web's`() {
        mapOf(
            "baduntss" to "48c30e2259598e3de9880d95b1075fcb7aa35a9b41ab94447f22534dc3b3d1e0",
            "bell" to "d489ccd5e386072e9db47c9a332ef4f33130cf940e1ddde55d062835ae197bb8",
            "cat" to "cbf49e888bcf2213145fa43045c3493746c8b2fc3cbebadf6727a244ec60ac4d",
            "clap" to "b927e4bd51c781fb0232b7430b13aa56cf7e9e1bf8a3b049a0011312ce5c8830",
            "crickets" to "f7949e8f092a8653f3da9f4d117be8cbc61b68c580bb43f610838df0061da8de",
            "deer" to "e9fdba54df8e31091c311b61bc16413accaebfc5b3bf18c6b7761577ac354c6c",
            "dog" to "0b0ce7ea208cd900c11e4743e9c25a6633d7d6e750bd8d5d34f1d3119a469260",
            "generic" to "748e6d03a559ac909d04b9fa907fca3424c6bf00aa45bcad3f6a07238e58f6cb",
            "lightbulb" to "2471aac761484adb8c2485954016b93af907d14a4d03afdcffe0a5749f489bb0",
            "party" to "3ab05da72c326143027e1bae16e005512e94eb8985d978a10eb9016f088a6b79",
            "rock" to "337166ae2fb7cac96b8c5fd6d2872dfd88105fc59363101846f3d55c30986531",
            "wave" to "4391d019809c9951333cd8ab9f5329b039602334bb55f5855b7852321109a6ac",
        ).forEach { (name, hash) -> assertThat(sha256("element_call_reaction_$name.mp3")).isEqualTo(hash) }
    }

    private fun sha256(name: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(File("src/main/res/raw/$name").readBytes())
            .joinToString("") { "%02x".format(it) }
}
