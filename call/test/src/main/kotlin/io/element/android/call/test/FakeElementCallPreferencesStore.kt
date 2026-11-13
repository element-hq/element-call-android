/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test

import io.element.android.call.api.ElementCallPreferences
import io.element.android.call.api.ElementCallPreferencesStore
import io.element.android.call.api.rtc.MatrixRtcMembershipFormat
import io.element.android.call.api.rtc.MatrixRtcVideoCodec

/** In memory: reset whenever it is made, as the sample's fixtures want. */
class FakeElementCallPreferencesStore(
    override var showsReactions: Boolean = ElementCallPreferences.DEFAULT.showsReactions,
    override var playsReactionSounds: Boolean = ElementCallPreferences.DEFAULT.playsReactionSounds,
    override var soundEffectsVolume: Float = ElementCallPreferences.DEFAULT.soundEffectsVolume,
    override var membershipFormat: MatrixRtcMembershipFormat = ElementCallPreferences.DEFAULT.membershipFormat,
    override var videoCodec: MatrixRtcVideoCodec = ElementCallPreferences.DEFAULT.videoCodec,
) : ElementCallPreferencesStore
