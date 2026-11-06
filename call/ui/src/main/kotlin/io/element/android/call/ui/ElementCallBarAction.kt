/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** What the control bar offers beyond microphone, camera, audio output and hang-up, in the More sheet's order (feature-hq 009 R17). */
enum class ElementCallBarAction {
    RAISED_HAND,
    REACTIONS,
    SCREEN_SHARE,
}

/** What the control bar shows before hang-up: the bar keeps to five full-size controls (009 R17). */
@Immutable
sealed interface ElementCallBarSlot {
    /** The only extra action, as a button of its own: More over a single entry is a tap for nothing. */
    data class Action(val action: ElementCallBarAction) : ElementCallBarSlot

    /** Opens the More sheet with every extra action. */
    data object More : ElementCallBarSlot
}

/** What the control bar has open over the call, one at a time, so More hands over to the picker (009 R17). */
enum class ElementCallSheet {
    MORE,
    REACTIONS,
}

internal fun extraActions(
    showsRaiseHandControl: Boolean,
    showsReactionControl: Boolean,
    isScreenShareAvailable: Boolean,
): ImmutableList<ElementCallBarAction> = buildList {
    if (showsRaiseHandControl) add(ElementCallBarAction.RAISED_HAND)
    if (showsReactionControl) add(ElementCallBarAction.REACTIONS)
    if (isScreenShareAvailable) add(ElementCallBarAction.SCREEN_SHARE)
}.toImmutableList()

internal fun List<ElementCallBarAction>.toBarSlot(): ElementCallBarSlot? = when (size) {
    0 -> null
    1 -> ElementCallBarSlot.Action(single())
    else -> ElementCallBarSlot.More
}
