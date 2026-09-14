/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api

import android.app.PendingIntent
import android.content.Context
import androidx.annotation.DrawableRes

/**
 * The identity of the ongoing-call notification the foreground service posts.
 *
 * The host owns notification ids and channels process-wide: Element X gives the native call the same
 * id as its WebView call, so the two paths cannot leave two ongoing-call notifications in the shade.
 * The defaults are for a host with no such concern, the sample app included.
 */
data class ElementCallNotificationConfig(
    val notificationId: Int = DEFAULT_NOTIFICATION_ID,
    val channelId: String = DEFAULT_CHANNEL_ID,
    /** The channel's user-visible name, created on first use on Android O and above. Null uses the library's string. */
    val channelName: CharSequence? = null,
    /** The status bar icon. Null uses the library's handset glyph. */
    @DrawableRes val smallIcon: Int? = null,
    /** The icon of the notification's mute action while the microphone is on. Null uses the library's glyph. */
    @DrawableRes val muteIcon: Int? = null,
    /** The icon of the notification's unmute action while the microphone is muted. Null uses the library's glyph. */
    @DrawableRes val unmuteIcon: Int? = null,
    /**
     * What tapping the notification opens: the way back into the running call. Null falls back to the
     * app's launch intent, which is right for a host that draws the call in its main Activity.
     */
    val contentIntent: ((Context) -> PendingIntent?)? = null,
) {
    companion object {
        const val DEFAULT_NOTIFICATION_ID = 0x0E1C_A11
        const val DEFAULT_CHANNEL_ID = "element_call_ongoing"
    }
}
