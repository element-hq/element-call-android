/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test

import io.element.android.call.api.ElementCallPreferences
import io.element.android.call.api.ElementCallPreferencesStore
import io.element.android.call.api.ElementCallReactionsPreference

/** In memory: reset whenever it is made, as the sample's fixtures want. */
class FakeElementCallPreferencesStore(
    override var reactions: ElementCallReactionsPreference = ElementCallPreferences.DEFAULT.reactions,
    override var soundEffectsVolume: Float = ElementCallPreferences.DEFAULT.soundEffectsVolume,
) : ElementCallPreferencesStore
