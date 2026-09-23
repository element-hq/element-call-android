/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.receivers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.PendingIntentCompat

/** The [ElementCallActionReceiver] broadcasts, shared by the notification and picture-in-picture. */
internal object ElementCallActionIntents {
    private const val HANG_UP_REQUEST_CODE = 0
    private const val TOGGLE_MUTE_REQUEST_CODE = 1

    fun hangUp(context: Context): PendingIntent = broadcast(context, ElementCallActionReceiver.ACTION_HANG_UP, HANG_UP_REQUEST_CODE)

    fun toggleMute(context: Context): PendingIntent = broadcast(context, ElementCallActionReceiver.ACTION_TOGGLE_MUTE, TOGGLE_MUTE_REQUEST_CODE)

    private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, ElementCallActionReceiver::class.java).setAction(action)
        // Explicitly not immutable: mutability is irrelevant here since the receiver reads no extras,
        // but PendingIntentCompat wants the choice made rather than defaulted.
        return PendingIntentCompat.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT, false)!!
    }
}
