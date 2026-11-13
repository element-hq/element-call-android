/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import io.element.android.call.api.rtc.MatrixRtcMembershipFormat
import io.element.android.call.api.rtc.MatrixRtcVideoCodec

/**
 * Semantics test tags on the composables a UI test has to reach by hand rather than by label: the
 * things with no text and no content description, because they are pictures.
 */
object ElementCallTestTags {
    /** One tile in the call screen's layout, by its [CallTileData.tileId]. */
    fun tile(tileId: String): String = "element_call_tile_$tileId"

    /** The hero stack's arrows, in landscape, and its "1 of n" position pill. */
    const val HERO_PREVIOUS = "element_call_hero_previous"
    const val HERO_NEXT = "element_call_hero_next"
    const val HERO_INDICATOR = "element_call_hero_indicator"

    /** The fullscreen HUD's close button, which leaves fullscreen and is not the minimise button (spec 000 R12). */
    const val EXIT_FULLSCREEN = "element_call_exit_fullscreen"

    /** The top bar's settings button, which opens the settings menu (feature-hq 043 R1). */
    const val SETTINGS = "element_call_settings"

    /** The settings menu's Preferences item and the sheet it opens (feature-hq 037, 043), named as iOS names them. */
    const val PREFERENCES = "element_call_preferences"
    const val PREFERENCES_SHEET = "element_call_preferences_sheet"
    const val PREFERENCES_SHOW_REACTIONS = "element_call_preferences_show_reactions"
    const val PREFERENCES_PLAY_REACTION_SOUNDS = "element_call_preferences_play_reaction_sounds"
    const val PREFERENCES_SOUND_EFFECTS_VOLUME = "element_call_preferences_sound_effects_volume"
    const val PREFERENCES_VERSION = "element_call_preferences_version"

    /** The settings menu's Developer options item and the sheet it opens, in developer mode only (043 R3, R4). */
    const val DEVELOPER_OPTIONS = "element_call_developer_options"
    const val DEVELOPER_OPTIONS_SHEET = "element_call_developer_options_sheet"
    const val DEVELOPER_OPTIONS_TILE_STATS = "element_call_developer_options_tile_stats"

    /** One membership format's row (043 R11). */
    fun developerOptionsMembershipFormat(format: MatrixRtcMembershipFormat): String =
        "element_call_developer_options_membership_format_${format.name.lowercase()}"

    /** One video codec's row, Auto included (043 R15). */
    fun developerOptionsVideoCodec(codec: MatrixRtcVideoCodec): String = "element_call_developer_options_video_codec_${codec.name.lowercase()}"

    /** A failed MSC join's way back (043 R18a). */
    const val FAILURE_USE_ROOM_STATE = "element_call_failure_use_room_state"

    /** The bar's fifth control and what it opens (feature-hq 009 R17), spelt as iOS spells them for the interop rig. */
    const val CONTROLS_MORE = "controlsMore"
    const val MORE_SHEET = "moreSheet"

    /** The hand and the share, as the bar's own control or as the More sheet's row. */
    const val RAISED_HAND = "raisedHand"
    const val SCREEN_SHARE = "screenShare"

    /** What opens the picker, the bar's control or the More sheet's row; the picker; each reaction by its catalogue name. */
    const val REACTIONS = "reactions"
    const val REACTION_SHEET = "reactionSheet"

    fun reaction(name: String): String = "reaction.$name"

    /** The raised-hand banner's Cancel, which lowers our hand. */
    const val LOWER_HAND = "lowerHand"
}
