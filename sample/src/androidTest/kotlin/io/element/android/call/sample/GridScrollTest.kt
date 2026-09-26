/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.call.ui.ElementCallTestTags
import io.element.android.call.ui.aCrowdMemberId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A vertical swipe on a grid tile scrolls the grid rather than being eaten by the tile's own
 * gestures (spec 003 R26). The grid's scrolling and the tiles' click and long press share one
 * touch pipeline, and only a device arbitrates it the way a finger does.
 */
@RunWith(AndroidJUnit4::class)
class GridScrollTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun swipingAGridTileScrollsTheGrid() {
        launchSample(SampleFixture.LARGE_CALL).use {
            val tag = ElementCallTestTags.tile(aCrowdMemberId(1))
            val before = composeRule.onNodeWithTag(tag).getBoundsInRoot()

            composeRule.onNodeWithTag(tag).performTouchInput { swipeUp() }
            composeRule.waitForIdle()

            // Scrolled away: either the tile has left the band and is no longer composed at all, or
            // it is still composed and has moved up.
            val remaining = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes()
            if (remaining.isNotEmpty()) {
                val after = composeRule.onNodeWithTag(tag).getBoundsInRoot()
                assertThat(after.top.value).isLessThan(before.top.value)
            }
        }
    }
}
