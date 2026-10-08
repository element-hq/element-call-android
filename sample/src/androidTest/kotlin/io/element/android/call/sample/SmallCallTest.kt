/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import io.element.android.call.ui.ElementCallTestTags
import io.element.android.call.ui.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Our floating tile under a real touch (spec 019): the drag, the release and the camera button share
 * one surface, which is the class of bug only a real touch pipeline shows.
 */
@RunWith(AndroidJUnit4::class)
class SmallCallTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun ourTileStartsBottomRightAndADropTakesItToTheNearestCorner() {
        launchSample(SampleFixture.ONE_TO_ONE).use {
            val before = ownTile()
            assertThat(before.isRight()).isTrue()
            assertThat(before.isBottom()).isTrue()

            dragOwnTile(from = ownTileCentre(), by = Offset(-STEP, -STEP))

            val after = ownTile()
            assertThat(after.isRight()).isFalse()
            assertThat(after.isBottom()).isFalse()
        }
    }

    @Test
    fun aTapOnTheCameraButtonDoesNotMoveOurTile() {
        launchSample(SampleFixture.ONE_TO_ONE).use {
            val before = ownTile()

            composeRule.onNodeWithContentDescription(switchCamera()).performClick()
            composeRule.waitForIdle()

            assertThat(ownTile()).isEqualTo(before)
        }
    }

    @Test
    fun aDragThatStartsOnTheCameraButtonMovesOurTile() {
        launchSample(SampleFixture.ONE_TO_ONE).use {
            val button = composeRule.onNodeWithContentDescription(switchCamera()).getBoundsInRoot()
            val start = with(composeRule.density) { Offset(((button.left + button.right) / 2).toPx(), ((button.top + button.bottom) / 2).toPx()) }

            dragOwnTile(from = start, by = Offset(-STEP, -STEP))

            assertThat(ownTile().isBottom()).isFalse()
        }
    }

    @Test
    fun fromThreeTilesOurTileIsInlineAndTheSameSizeAsTheOthers() {
        launchSample(SampleFixture.SMALL_THREE).use {
            val own = ownTile()
            val carol = composeRule.onNodeWithTag(ElementCallTestTags.tile(SampleFixture.memberIdOf("Carol"))).getBoundsInRoot()

            assertThat(own.right - own.left).isEqualTo(carol.right - carol.left)
            assertThat(own.top).isLessThan(carol.top)
        }
    }

    private fun ownTile(): DpRect = composeRule.onNodeWithTag(ElementCallTestTags.tile(SampleFixture.memberIdOf("Alice"))).getBoundsInRoot()

    private fun ownTileCentre(): Offset {
        val tile = ownTile()
        return with(composeRule.density) { Offset(((tile.left + tile.right) / 2).toPx(), ((tile.top + tile.bottom) / 2).toPx()) }
    }

    /** Slowly, in steps, with a rest at the end: a drop, so the corner is the nearest one (019 R21). */
    private fun dragOwnTile(from: Offset, by: Offset) {
        composeRule.onRoot().performTouchInput {
            down(from)
            repeat(DRAG_STEPS) { moveBy(by, delayMillis = 50) }
            advanceEventTime(300)
            up()
        }
        composeRule.waitForIdle()
    }

    private fun DpRect.isRight() = (left + right) / 2 > rootWidthHalf()

    private fun DpRect.isBottom() = (top + bottom) / 2 > rootHeightHalf()

    private fun rootWidthHalf() = composeRule.onRoot().getBoundsInRoot().let { (it.left + it.right) / 2 }

    private fun rootHeightHalf() = composeRule.onRoot().getBoundsInRoot().let { (it.top + it.bottom) / 2 }

    private fun switchCamera() = ApplicationProvider.getApplicationContext<Context>().getString(R.string.element_call_a11y_switch_camera)

    private companion object {
        const val DRAG_STEPS = 8
        const val STEP = 150f
    }
}
