/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import com.google.common.truth.Truth.assertThat
import io.element.android.call.ui.ElementCallChromeVisibility.Event.FullscreenEnded
import io.element.android.call.ui.ElementCallChromeVisibility.Event.Restored
import io.element.android.call.ui.ElementCallChromeVisibility.Event.Rotated
import io.element.android.call.ui.ElementCallChromeVisibility.Event.ScreenReader
import io.element.android.call.ui.ElementCallChromeVisibility.Event.ScrollIdleElapsed
import io.element.android.call.ui.ElementCallChromeVisibility.Event.StageAppeared
import io.element.android.call.ui.ElementCallChromeVisibility.Event.Tap
import io.element.android.call.ui.ElementCallChromeVisibility.Event.UserScrolled
import org.junit.Test

/** When the stage's chrome is up: spec 014, rule by rule. The value is the whole decision, so the rules are tested here. */
class ElementCallChromeVisibilityTest {
    private fun chrome(vararg events: ElementCallChromeVisibility.Event) = events.fold(
        ElementCallChromeVisibility.Initial
    ) { chrome, event -> chrome.apply(event) }

    @Test
    fun `shown before the stage exists, whatever comes (R13)`() {
        assertThat(chrome().isVisible).isTrue()
        assertThat(chrome(UserScrolled(towardEnd = true)).isVisible).isTrue()
        assertThat(chrome(Restored).isVisible).isTrue()
    }

    @Test
    fun `starts hidden in landscape and shown in portrait (R10, R11)`() {
        assertThat(chrome(StageAppeared(isLandscape = true)).isVisible).isFalse()
        assertThat(chrome(StageAppeared(isLandscape = false)).isVisible).isTrue()
    }

    @Test
    fun `a rotation resets to the new orientation's start (R12)`() {
        assertThat(chrome(StageAppeared(isLandscape = false), Rotated(isLandscape = true)).isVisible).isFalse()
        assertThat(chrome(StageAppeared(isLandscape = true), Tap, Tap, Rotated(isLandscape = false)).isVisible).isTrue()
        // Tapped up in landscape, round trip: back to landscape's start, not to what it was.
        assertThat(chrome(StageAppeared(isLandscape = true), Tap, Rotated(isLandscape = false), Rotated(isLandscape = true)).isVisible).isFalse()
    }

    @Test
    fun `a resize that keeps the shape is not a rotation`() {
        assertThat(chrome(StageAppeared(isLandscape = true), Tap, Rotated(isLandscape = true)).isVisible).isTrue()
        assertThat(chrome(StageAppeared(isLandscape = false), Tap, Rotated(isLandscape = false)).isVisible).isFalse()
    }

    @Test
    fun `a tap toggles, in both orientations (R14)`() {
        assertThat(chrome(StageAppeared(isLandscape = false), Tap).isVisible).isFalse()
        assertThat(chrome(StageAppeared(isLandscape = false), Tap, Tap).isVisible).isTrue()
        assertThat(chrome(StageAppeared(isLandscape = true), Tap).isVisible).isTrue()
        assertThat(chrome(StageAppeared(isLandscape = true), Tap, Tap).isVisible).isFalse()
    }

    @Test
    fun `in portrait, scrolling toward the end hides and toward the start shows (R18, R19)`() {
        val hidden = chrome(StageAppeared(isLandscape = false), UserScrolled(towardEnd = true))
        assertThat(hidden.isVisible).isFalse()
        assertThat(hidden.hiddenBy).isEqualTo(ElementCallChromeVisibility.Reason.SCROLL)
        assertThat(hidden.apply(UserScrolled(towardEnd = false)).isVisible).isTrue()
    }

    @Test
    fun `scrolling toward the start also undoes a tap-hide (R21)`() {
        assertThat(chrome(StageAppeared(isLandscape = false), Tap, UserScrolled(towardEnd = false)).isVisible).isTrue()
    }

    @Test
    fun `scroll-hidden chrome comes back once the user stops (R20)`() {
        val hidden = chrome(StageAppeared(isLandscape = false), UserScrolled(towardEnd = true))
        assertThat(hidden.isAwaitingReturn).isTrue()
        val returned = hidden.apply(ScrollIdleElapsed)
        assertThat(returned.isVisible).isTrue()
        assertThat(returned.isAwaitingReturn).isFalse()
    }

    @Test
    fun `tap-hidden chrome never comes back by itself, scrolled on or not (R21)`() {
        assertThat(chrome(StageAppeared(isLandscape = false), Tap).isAwaitingReturn).isFalse()
        assertThat(chrome(StageAppeared(isLandscape = false), Tap, ScrollIdleElapsed).isVisible).isFalse()
        val scrolledOn = chrome(StageAppeared(isLandscape = false), Tap, UserScrolled(towardEnd = true), ScrollIdleElapsed)
        assertThat(scrolledOn.isVisible).isFalse()
        assertThat(scrolledOn.hiddenBy).isEqualTo(ElementCallChromeVisibility.Reason.TAP)
    }

    @Test
    fun `in landscape scrolling changes nothing and nothing returns by itself (R22)`() {
        assertThat(chrome(StageAppeared(isLandscape = true), Tap, UserScrolled(towardEnd = true)).isVisible).isTrue()
        assertThat(chrome(StageAppeared(isLandscape = true), UserScrolled(towardEnd = false)).isVisible).isFalse()
        assertThat(chrome(StageAppeared(isLandscape = true), ScrollIdleElapsed).isVisible).isFalse()
        assertThat(chrome(StageAppeared(isLandscape = true)).isAwaitingReturn).isFalse()
    }

    @Test
    fun `a screen reader forces it up and refuses every hide (R24)`() {
        assertThat(chrome(StageAppeared(isLandscape = true), ScreenReader(isRunning = true)).isVisible).isTrue()
        assertThat(chrome(ScreenReader(isRunning = true), StageAppeared(isLandscape = true)).isVisible).isTrue()
        assertThat(chrome(ScreenReader(isRunning = true), StageAppeared(isLandscape = false), Tap).isVisible).isTrue()
        assertThat(chrome(ScreenReader(isRunning = true), StageAppeared(isLandscape = false), UserScrolled(towardEnd = true)).isVisible).isTrue()
        assertThat(chrome(ScreenReader(isRunning = true), StageAppeared(isLandscape = false), Rotated(isLandscape = true)).isVisible).isTrue()
        // Once it stops, the ordinary rules are back.
        assertThat(chrome(ScreenReader(isRunning = true), StageAppeared(isLandscape = false), ScreenReader(isRunning = false), Tap).isVisible).isFalse()
    }

    @Test
    fun `leaving fullscreen yourself returns to the orientation's start (R27)`() {
        assertThat(chrome(StageAppeared(isLandscape = true), Tap, FullscreenEnded(byDeparture = false)).isVisible).isFalse()
        assertThat(chrome(StageAppeared(isLandscape = false), Tap, FullscreenEnded(byDeparture = false)).isVisible).isTrue()
    }

    @Test
    fun `fullscreen ended by a departure shows the chrome, in both orientations (R28)`() {
        assertThat(chrome(StageAppeared(isLandscape = true), FullscreenEnded(byDeparture = true)).isVisible).isTrue()
        assertThat(chrome(StageAppeared(isLandscape = false), Tap, FullscreenEnded(byDeparture = true)).isVisible).isTrue()
    }

    @Test
    fun `coming back from minimized returns to the orientation's start (R29)`() {
        assertThat(chrome(StageAppeared(isLandscape = true), Tap, Restored).isVisible).isFalse()
        assertThat(chrome(StageAppeared(isLandscape = false), Tap, Restored).isVisible).isTrue()
    }
}
