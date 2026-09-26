/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

/**
 * Drives the call stage the way a finger would, for a harness playing a layout scenario on a
 * device: scroll the grid, switch the shown hero, enter and leave fullscreen. Provided to the
 * composition with [ElementCallStageDriverProvider]; a host never needs one.
 *
 * Every call is made on the main thread, as the scenario player does.
 */
class ElementCallStageDriver {
    internal val hooks = CallStageTestHooks()
    internal var density: Float = 1f

    /** Whether a stage is currently drawn, and so has anything to drive. */
    val isStageMounted: Boolean get() = hooks.isMounted

    /** Scroll the grid to [offsetDp] from the top, clamped as a user's scroll would be. */
    fun scrollTo(offsetDp: Float) = hooks.scrollTo(offsetDp * density)

    fun showNextHero() = showHero(1)

    fun showPreviousHero() = showHero(-1)

    /** Fill the stage with a tile, or bring it back if it already does (spec 000 R1, R2). */
    fun toggleFullscreen(memberId: String, isScreenShare: Boolean) {
        val tileId = if (isScreenShare) "$memberId#SCREEN_SHARE" else memberId
        hooks.eventSink(ElementCallScreenEvent.ToggleFullscreen(tileId))
    }

    fun exitFullscreen() = hooks.eventSink(ElementCallScreenEvent.ExitFullscreen)

    private fun showHero(step: Int) {
        val shown = hooks.heroes.indexOf(hooks.spotlightTileId)
        val target = hooks.heroes.getOrNull(shown + step) ?: return
        hooks.eventSink(ElementCallScreenEvent.ShowHero(target))
    }
}
