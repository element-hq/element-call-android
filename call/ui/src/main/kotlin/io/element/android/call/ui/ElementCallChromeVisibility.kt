/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

/**
 * Whether the stage's chrome is up, and why not when it is not (spec 014). In landscape the chrome is
 * the top bar and the control bar together (R1); in portrait the top bar stays and it is the control
 * bar alone (R30).
 *
 * A value with one entry point rather than a flag, because what happens next depends on how it got
 * here: chrome hidden by scrolling comes back by itself, chrome hidden by a tap does not (R20, R21).
 * Separate from the fullscreen HUD's flag: fullscreen keeps its HUD across a rotation (000 R10) and the
 * stage resets on one (R12).
 */
internal data class ElementCallChromeVisibility(
    val isVisible: Boolean,
    /** Why it is hidden; null while it is visible. */
    val hiddenBy: Reason?,
    /** The orientation it was last reset for; null until the stage first appears. */
    val isLandscape: Boolean?,
    val isScreenReaderRunning: Boolean,
) {
    enum class Reason { TAP, SCROLL }

    sealed interface Event {
        /** The stage is up; before this the screen draws the chrome regardless (R13). */
        data class StageAppeared(val isLandscape: Boolean) : Event

        /** The shape of the space changed. Only a change of orientation resets anything. */
        data class Rotated(val isLandscape: Boolean) : Event

        /** A single tap on the stage (R14). */
        data object Tap : Event

        /** The user's own scrolling, never the app's (R23). Portrait only (R22). */
        data class UserScrolled(val towardEnd: Boolean) : Event

        /** [CHROME_RETURN_DELAY_MS] has passed since the user's scrolling stopped (R20). */
        data object ScrollIdleElapsed : Event

        /** A tile stopped being fullscreen: left by the user, or by its member leaving (R27, R28). */
        data class FullscreenEnded(val byDeparture: Boolean) : Event

        /** Back from Picture in Picture or the minimized bar (R29). */
        data object Restored : Event

        data class ScreenReader(val isRunning: Boolean) : Event
    }

    /** Whether the screen should be counting down to [Event.ScrollIdleElapsed]. */
    val isAwaitingReturn: Boolean get() = hiddenBy == Reason.SCROLL

    fun apply(event: Event): ElementCallChromeVisibility = when (event) {
        is Event.StageAppeared -> reset(event.isLandscape)
        // A resize that keeps the shape is not a rotation: a resizable window would undo every tap.
        is Event.Rotated -> if (event.isLandscape == isLandscape) this else reset(event.isLandscape)
        Event.Tap -> if (isVisible) hide(Reason.TAP) else show()
        is Event.UserScrolled -> when {
            isLandscape != false -> this
            !event.towardEnd -> show()
            // Only from visible: scrolling on past a tap-hide must not make it one that returns by itself.
            isVisible -> hide(Reason.SCROLL)
            else -> this
        }
        Event.ScrollIdleElapsed -> if (isAwaitingReturn) show() else this
        is Event.FullscreenEnded -> when {
            // The user did not choose to leave, so they see where they landed and the way on (R28).
            event.byDeparture -> show()
            isLandscape != null -> reset(isLandscape)
            else -> this
        }
        Event.Restored -> isLandscape?.let { reset(it) } ?: this
        is Event.ScreenReader -> copy(isScreenReaderRunning = event.isRunning).let { if (event.isRunning) it.show() else it }
    }

    /** Each orientation's starting state (R10, R11). Landscape's counts as a tap-hide: nothing brings it back but the user (R22). */
    private fun reset(isLandscape: Boolean) = copy(isLandscape = isLandscape).let { if (isLandscape) it.hide(Reason.TAP) else it.show() }

    private fun show() = copy(isVisible = true, hiddenBy = null)

    /** Refused while a screen reader runs: a control TalkBack cannot reach does not exist for it (R24). */
    private fun hide(reason: Reason) = if (isScreenReaderRunning) show() else copy(isVisible = false, hiddenBy = reason)

    companion object {
        val Initial = ElementCallChromeVisibility(isVisible = true, hiddenBy = null, isLandscape = null, isScreenReaderRunning = false)
    }
}

/** How long chrome hidden by a scroll stays away once the scrolling has stopped (R20). */
internal const val CHROME_RETURN_DELAY_MS = 2_000L

/** How long a single tap waits for a second one before it toggles the chrome: shorter than the system's double-tap timeout (R16). */
internal const val CHROME_TAP_DELAY_MS = 200L
