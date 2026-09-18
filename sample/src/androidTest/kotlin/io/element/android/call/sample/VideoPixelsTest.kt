/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.os.SystemClock
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.call.ui.A_REMOTE_MEMBER_ID
import io.element.android.call.ui.ElementCallTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one thing that proves the sample's frames go through the real renderer: the pixels of a video
 * tile are not one colour. An avatar, a placeholder or a dead GL surface all fail this; the colour
 * bars pass it. Everything upstream - the frame's planes, the libwebrtc buffer wrapper, the GL thread,
 * the texture view - has to work for a single test to go green.
 */
@RunWith(AndroidJUnit4::class)
class VideoPixelsTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun aVideoTileDrawsTheColourBars() {
        launchSample(SampleFixture.ONE_TO_ONE).use {
            val tile = composeRule.onNodeWithTag(ElementCallTestTags.tile(A_REMOTE_MEMBER_ID))

            // The first frame takes a moment: the GL thread has to come up and the pattern runs at 15fps.
            var colours = 0
            val deadline = SystemClock.elapsedRealtime() + FIRST_FRAME_TIMEOUT_MS
            while (colours < MIN_COLOURS && SystemClock.elapsedRealtime() < deadline) {
                composeRule.waitForIdle()
                colours = tile.captureToImage().distinctColours()
                if (colours < MIN_COLOURS) SystemClock.sleep(POLL_MS)
            }

            assertThat(colours).isAtLeast(MIN_COLOURS)
        }
    }

    private companion object {
        /** Eight bars plus the border; four is far more than anything that is not a picture. */
        const val MIN_COLOURS = 4
        const val FIRST_FRAME_TIMEOUT_MS = 10_000L
        const val POLL_MS = 250L
    }
}
