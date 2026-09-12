/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui.preview

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/**
 * Marker for a night mode preview.
 *
 * Previews with such marker will be rendered in night mode during screenshot testing.
 *
 * NB: Length of this constant is kept to a minimum to avoid screenshot file names being too long.
 */
const val NIGHT_MODE_NAME = "Night"

/**
 * Marker for a day mode preview.
 *
 * This marker is currently not used during screenshot testing, it mainly act as a counterpart to [NIGHT_MODE_NAME].
 *
 * NB: Length of this constant is kept to a minimum to avoid screenshot file names being too long.
 */
const val DAY_MODE_NAME = "Day"

/**
 * Generates 2 previews of the composable it is applied to: day and night mode. This is the annotation
 * the screenshot tests key on: a preview without it is not recorded. Same name as Element X's, in this
 * library's package, so a developer moving between the two repositories finds the same convention.
 *
 * NB: Content should be wrapped into [ElementCallPreview] to apply proper theming.
 */
@Preview(
    name = DAY_MODE_NAME,
    fontScale = 1f,
)
@Preview(
    name = NIGHT_MODE_NAME,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 1f,
)
annotation class PreviewsDayNight
