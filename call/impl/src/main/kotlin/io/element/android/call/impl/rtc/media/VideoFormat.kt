/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc.media

/** What we ask the screen capture for. The camera's format is [CameraCaptureFormat]'s. */
internal object VideoFormat {
    /**
     * The longest edge of a screen share, which is scaled to fit inside it.
     *
     * Well below a phone's real 1080x2400. Text has to stay readable, so this cannot go as low as
     * the camera, but every pixel is repacked in Kotlin on the way out (`FEEDBACK.md` item 14) and
     * native resolution would be five times the camera's load.
     */
    const val SCREEN_MAX_EDGE = 1280

    /**
     * Half the camera's rate. A screen is mostly still, and the frames that matter - a scroll, a
     * slide change - are still caught at 15, while the cost of a screen frame is several times that
     * of a camera frame.
     */
    const val SCREEN_FPS = 15
}
