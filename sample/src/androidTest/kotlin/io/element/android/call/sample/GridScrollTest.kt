/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.content.pm.ActivityInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import io.element.android.call.ui.ElementCallTestTags
import io.element.android.call.ui.R
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

    @Test
    fun swipingTheLandscapeStripBesideTheControlsScrollsIt() {
        launchSample(SampleFixture.TWO_SHARES).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            composeRule.waitForIdle()
            val root = composeRule.onRoot().getBoundsInRoot()
            assertThat(root.right).isGreaterThan(root.bottom)
            // The strip's lower tile, whose bottom the control bar floats over (R43).
            val tiles = composeRule.onAllNodes(isTile).fetchSemanticsNodes()
            val lower = tiles.filter { it.boundsInRoot.left > it.boundsInRoot.width }.maxBy { it.boundsInRoot.top }
            val tag = lower.config[SemanticsProperties.TestTag]
            val before = lower.boundsInRoot
            val barTop = composeRule.onNodeWithContentDescription(hangUp).getBoundsInRoot().top.value * density
            val start = Offset(before.center.x, (barTop + before.bottom) / 2)
            assertThat(start.y).isGreaterThan(barTop)

            composeRule.onRoot().performTouchInput { swipe(start, start.copy(y = before.top)) }
            composeRule.waitForIdle()

            val remaining = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes()
            if (remaining.isNotEmpty()) {
                assertThat(remaining.single().boundsInRoot.top).isLessThan(before.top)
            }
        }
    }

    @Test
    fun swipingAControlButtonInLandscapeDoesNotScroll() {
        launchSample(SampleFixture.TWO_SHARES).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            composeRule.waitForIdle()
            val tiles = { composeRule.onAllNodes(isTile).fetchSemanticsNodes().associate { it.config[SemanticsProperties.TestTag] to it.boundsInRoot } }
            val before = tiles()

            composeRule.onNodeWithContentDescription(hangUp).performTouchInput { swipeUp() }
            composeRule.waitForIdle()

            assertThat(tiles()).isEqualTo(before)
        }
    }

    private val isTile = SemanticsMatcher("is a tile") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(ElementCallTestTags.tile("")) == true
    }
    private val hangUp get() = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.element_call_a11y_hang_up)
    private val density get() = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
}
