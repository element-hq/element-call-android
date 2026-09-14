/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.callnative.api.NativeCallPip
import kotlinx.coroutines.flow.StateFlow

/**
 * Hands the Activity the two facts it needs, and nothing else.
 *
 * A thin delegate rather than [NativeCallController] implementing the interface directly, so that the
 * app module depends on the narrow api and cannot reach the rest of the controller from
 * `MainActivity`.
 */
@ContributesBinding(AppScope::class)
class DefaultNativeCallPip(
    private val controller: NativeCallController,
) : NativeCallPip {
    override val shouldEnterPictureInPicture: StateFlow<Boolean> = controller.shouldEnterPictureInPicture

    override fun setInPictureInPicture(isInPictureInPicture: Boolean) =
        controller.setInPictureInPicture(isInPictureInPicture)
}
