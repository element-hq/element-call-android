/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.Manifest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.element.android.call.ui.ElementCallTestTags
import io.element.android.call.ui.OWN_CAMERA_TILE_ID
import io.element.android.call.ui.R
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The lobby's fixtures on the real controller (feature-hq 045, contract C25), against real touches and the real clock:
 * what only a window, a touch or a screen reader can show. The rules themselves are `CallLobbyTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class LobbyTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun grantPermissions() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
    }

    /** R36, R12: the preview takes no tap or double tap; the call screen does not go fullscreen or hide its chrome. */
    @Test
    fun tapsOnThePreviewChangeNothing() {
        launchSample(SampleLobbyFixture.IN_PROGRESS, composeRule).use {
            composeRule.waitUntil(LOAD_TIMEOUT_MS) { isShown(context.getString(R.string.element_call_lobby_join)) }
            val preview = composeRule.onNodeWithTag(ElementCallTestTags.tile(OWN_CAMERA_TILE_ID))

            preview.performTouchInput { click() }
            Thread.sleep(SETTLE_MS)
            preview.performTouchInput { doubleClick() }
            Thread.sleep(SETTLE_MS)

            check(isTagged(ElementCallTestTags.LOBBY_CLOSE)) { "The lobby's chrome went away" }
            check(isTagged(ElementCallTestTags.LOBBY_JOIN)) { "Join went away" }
        }
    }

    /** R20, R37: system back closes the call screen, and nothing is left of the lobby. */
    @Test
    fun backClosesTheLobby() {
        launchSample(SampleLobbyFixture.EMPTY, composeRule).use {
            composeRule.waitUntil(LOAD_TIMEOUT_MS) { isShown(context.getString(R.string.element_call_lobby_start)) }
            Espresso.pressBackUnconditionally()
            waitUntil("the lobby closes") { isCallScreenGone() }
        }
    }

    /** R21, R41, R42, R57: the label, the count and the ended notice follow the call. */
    @Test
    fun theLabelFollowsTheCall() {
        launchSample(SampleLobbyFixture.COUNT_CHANGES, composeRule).use {
            composeRule.waitUntil(LOAD_TIMEOUT_MS) { isShown(context.getString(R.string.element_call_lobby_start)) }
            composeRule.waitUntil(COUNT_STEP_MS) { isShown(context.getString(R.string.element_call_lobby_join)) }
            composeRule.waitUntil(COUNT_STEP_MS) { isShown(context.getString(R.string.element_call_lobby_join_anyway)) }
            check(isTagged(ElementCallTestTags.LOBBY_CALL_ENDED)) { "The call-ended notice is missing" }
        }
    }

    /** R59, R60: loading shows no label and keeps Join off; a call that cannot be read ends on the failure screen. */
    @Test
    fun loadingHoldsJoinAndAFailureEndsIt() {
        launchSample(SampleLobbyFixture.LOADING, composeRule).use {
            composeRule.onNodeWithTag(ElementCallTestTags.LOBBY_JOIN).assertIsNotEnabled()
            check(!isShown(context.getString(R.string.element_call_lobby_join))) { "A label showed while loading" }
            composeRule.waitUntil(LOAD_TIMEOUT_MS) { isShown(context.getString(R.string.element_call_lobby_join)) }
        }
        launchSample(SampleLobbyFixture.UNREADABLE, composeRule).use {
            composeRule.waitUntil(LOAD_TIMEOUT_MS) { isShown(context.getString(R.string.element_call_call_failed)) }
        }
    }

    /** R23, R24, R49: the tap switches at once, and our tile is there throughout, before the core reports it and after. */
    @Test
    fun joinKeepsOurTileFromTheLobbyIntoTheCall() {
        launchSample(SampleLobbyFixture.JOIN, composeRule).use {
            composeRule.waitUntil(LOAD_TIMEOUT_MS) { isShown(context.getString(R.string.element_call_lobby_join)) }
            composeRule.onNodeWithTag(ElementCallTestTags.LOBBY_JOIN).performClick()
            composeRule.waitUntil(ONE_FRAME_MS) { !isTagged(ElementCallTestTags.LOBBY_CLOSE) }
            repeat(OWN_TILE_CHECKS) {
                check(isTagged(ElementCallTestTags.tile(OWN_CAMERA_TILE_ID))) { "Our tile went away after the tap" }
                Thread.sleep(SETTLE_MS)
            }
        }
    }

    /** R35: Join is where a screen reader starts. */
    @Test
    fun joinIsReadFirst() {
        launchSample(SampleLobbyFixture.IN_PROGRESS, composeRule).use {
            composeRule.onNodeWithTag(ElementCallTestTags.LOBBY_JOIN)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.TraversalIndex, -1f))
        }
    }

    private fun isShown(text: String) =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() ||
            composeRule.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()

    private fun isTagged(tag: String) = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val LOAD_TIMEOUT_MS = 6_000L
        const val COUNT_STEP_MS = 7_000L
        const val SETTLE_MS = 600L
        const val ONE_FRAME_MS = 100L
        const val OWN_TILE_CHECKS = 5
    }
}
