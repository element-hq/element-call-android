/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.call.ui.ElementCallTestTags
import io.element.android.call.ui.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The gestures the floating tile lives by, which only a real touch pipeline can exercise: a drag that
 * snaps to the nearer edge, and a tap that brings the call back. Robolectric can compose the tile but
 * cannot move it.
 */
@RunWith(AndroidJUnit4::class)
class FloatingTileGestureTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun draggingTheTileAcrossTheScreenSnapsItToTheOtherEdge() {
        launchSample(SampleFixture.FLOATING_TILE).use {
            val tile = composeRule.onNodeWithTag(ElementCallTestTags.FLOATING_TILE)
            val before = tile.getBoundsInRoot()

            // Eight tile widths to the left: further than any phone is wide, so the tile's centre is
            // past the middle whatever the screen, and the snap goes to the left margin.
            tile.performTouchInput {
                down(center)
                repeat(DRAG_STEPS) { moveBy(Offset(-width.toFloat(), 0f)) }
                up()
            }
            composeRule.waitForIdle()

            val after = composeRule.onNodeWithTag(ElementCallTestTags.FLOATING_TILE).getBoundsInRoot()
            assertThat(after.left.value).isLessThan(before.left.value)
        }
    }

    @Test
    fun tappingTheTileBringsTheCallBackFullScreen() {
        launchSample(SampleFixture.FLOATING_TILE).use {
            composeRule.onNodeWithTag(ElementCallTestTags.FLOATING_TILE).performClick()
            composeRule.waitForIdle()

            val hangUp = ApplicationProvider.getApplicationContext<android.content.Context>().getString(R.string.element_call_a11y_hang_up)
            composeRule.onNodeWithContentDescription(hangUp).assertIsDisplayed()
        }
    }

    private companion object {
        const val DRAG_STEPS = 8
    }
}
