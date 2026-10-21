/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.element.android.call.ui.ElementCallActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A call answered on a locked phone, as a host does it: the call starts, and a full-screen notification
 * opens the call screen over the keyguard. The delay is for the person to lock the phone.
 */
internal object SampleLockScreenCall {
    fun schedule(context: Context, harness: SampleHarness) {
        val appContext = context.applicationContext
        harness.scope.launch {
            delay(DELAY_MS)
            harness.stopScenario()
            harness.controller.start(SampleFixture.GROUP.snapshot())
            notify(appContext)
        }
    }

    private fun notify(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Timber.w("Sample: no notification permission, the call screen cannot open over the lock screen")
            return
        }
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH).setName("Incoming call").build(),
        )
        val callScreen = ElementCallActivity.pendingIntent(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Group call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(callScreen, true)
            .setContentIntent(callScreen)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private const val CHANNEL_ID = "sample_incoming_call"
    private const val NOTIFICATION_ID = 1
    private const val DELAY_MS = 5_000L
    private const val TIMEOUT_MS = 30_000L
}
