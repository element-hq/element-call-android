/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import io.element.android.call.api.ElementCallNotificationConfig
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.impl.ElementCallStackRegistry
import io.element.android.call.impl.R
import io.element.android.call.impl.receivers.ElementCallActionIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps the process alive and the microphone and camera usable while a native call is running.
 *
 * Android will not let a backgrounded app hold the microphone without a running foreground service
 * of type `microphone`, so this must be up before capture starts. The camera has the same rule and
 * its own type, but the two are asked for at different times: the microphone up front, the camera
 * only if the user turns it on. So the type is computed from what has actually been granted rather
 * than declared once - `startForeground` throws if a type's permission is missing - and starting the
 * service again once the camera is granted is what upgrades it. Restarting is cheap and idempotent:
 * `onStartCommand` runs again on the same instance.
 *
 * The notification's id, channel, icons and content intent come from the host's
 * [ElementCallNotificationConfig], so that a host with a second call path can share one id with it.
 */
class ElementCallForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationUpdates: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stack = ElementCallStackRegistry.active() ?: run {
            // The system restarted a sticky service after the process died and the host has not
            // rebuilt a stack yet: there is no call to keep alive.
            Timber.w("ElementCall: foreground service started with no stack alive, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        val config = stack.options.notification
        val isProjecting = intent?.getBooleanExtra(EXTRA_IS_PROJECTING, false) == true
        createNotificationChannel(config)
        val content = stack.controller.state.value.notificationContent()
        val notification = buildNotification(content, config)

        return try {
            ServiceCompat.startForeground(
                this,
                config.notificationId,
                notification,
                grantedServiceTypes(this, isProjecting),
            )
            followCall(stack.controller.state, config)
            START_STICKY
        } catch (throwable: Throwable) {
            // Most likely the microphone permission was revoked mid-call, which makes a microphone
            // foreground service illegal to start.
            Timber.w(throwable, "ElementCall: cannot start foreground service")
            stopSelf()
            START_NOT_STICKY
        }
    }

    /**
     * The ongoing-call notification: who the call is with, a way back into it, and a way out of it.
     *
     * `CallStyle.forOngoingCall` rather than a plain notification, which is what makes Android treat
     * this as a call - it gets the call treatment in the shade, on the lock screen and in the status
     * bar chip, and it is what a user expects to find when they leave the app mid-call. The hang-up
     * action is part of the style rather than added: `CallStyle` insists on one, which is the right
     * insistence.
     *
     * Re-posted by [followCall] whenever what it shows changes, so the mute button and the title
     * follow the call rather than whatever they were when the service last started.
     */
    private fun buildNotification(content: NotificationContent, config: ElementCallNotificationConfig): Notification {
        val isMuted = content.isMuted
        val title = content.roomName?.takeIf { it.isNotBlank() } ?: getString(R.string.element_call_notification_call_in_progress)
        // CallStyle needs somebody to name the call after, and for a room that is the room.
        val caller = Person.Builder().setName(title).setImportant(true).build()

        val hangUpIntent = ElementCallActionIntents.hangUp(this)
        val muteIntent = ElementCallActionIntents.toggleMute(this)

        return NotificationCompat.Builder(this, config.channelId)
            .setSmallIcon(config.smallIcon ?: R.drawable.ic_element_call_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(caller, hangUpIntent))
            .addPerson(caller)
            // Tapping it comes back to the call rather than doing nothing. The host says where the
            // call is drawn; by default its launch Activity, which is where a host that draws the call
            // in its main Activity wants to land.
            .setContentIntent(config.contentIntent?.invoke(this) ?: returnToAppIntent())
            .addAction(
                NotificationCompat.Action.Builder(
                    if (isMuted) {
                        config.unmuteIcon ?: R.drawable.ic_element_call_notification_mic_off
                    } else {
                        config.muteIcon ?: R.drawable.ic_element_call_notification_mic_on
                    },
                    getString(if (isMuted) R.string.element_call_notification_unmute_microphone else R.string.element_call_notification_mute_microphone),
                    muteIntent,
                ).build()
            )
            .build()
    }

    /**
     * Keeps the posted notification in step with the call. Re-posting under the foreground id updates
     * it in place, where starting the service again would be refused from the background.
     */
    private fun followCall(state: Flow<ElementCallSnapshot?>, config: ElementCallNotificationConfig) {
        notificationUpdates?.cancel()
        notificationUpdates = scope.launch {
            val context = this@ElementCallForegroundService
            state.notificationContentChanges().collect { content ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    return@collect
                }
                NotificationManagerCompat.from(context).notify(config.notificationId, buildNotification(content, config))
            }
        }
    }

    /**
     * Back into the host app. With a `singleTask` main Activity this brings the existing task forward
     * rather than building a second one - the call is already drawn inside it and simply becomes
     * visible again.
     */
    private fun returnToAppIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: return null
        return PendingIntentCompat.getActivity(this, RETURN_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT, false)
    }

    private fun createNotificationChannel(config: ElementCallNotificationConfig) {
        // Channels arrived in O, and the library supports 24. Touching NotificationChannel below that
        // throws before the service can start its own notification.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            config.channelId,
            config.channelName ?: getString(R.string.element_call_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val EXTRA_IS_PROJECTING = "is_projecting"
        private const val RETURN_REQUEST_CODE = 2

        private fun isGranted(context: Context, permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        /**
         * The declared types whose permission we actually hold. Never empty when [start] got this
         * far, because the microphone is checked there first.
         *
         * [isProjecting] is not a permission and cannot be checked for: screen capture is granted as
         * a one-shot token from a system dialog, so the caller has to say. It is claimed *before* the
         * projection is, which is the order Android 14 requires, and dropped as soon as sharing stops
         * so the screen-recording indicator does not linger over a call that is only using a camera.
         * The host must have declared the type in its manifest (README, host requirements).
         */
        private fun grantedServiceTypes(context: Context, isProjecting: Boolean): Int {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (isGranted(context, Manifest.permission.CAMERA)) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (isProjecting && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            return types
        }

        fun start(context: Context, isProjecting: Boolean = false) {
            // Starting a microphone foreground service without the permission throws, so bail out
            // early and let the call run only while the Activity is in the foreground.
            if (!isGranted(context, Manifest.permission.RECORD_AUDIO)) {
                Timber.w("ElementCall: not starting foreground service, no microphone permission")
                return
            }
            val intent = Intent(context, ElementCallForegroundService::class.java)
                .putExtra(EXTRA_IS_PROJECTING, isProjecting)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ElementCallForegroundService::class.java))
        }
    }
}

internal data class NotificationContent(
    val roomName: String?,
    val isMuted: Boolean,
)

internal fun ElementCallSnapshot?.notificationContent() = NotificationContent(
    roomName = this?.roomName,
    isMuted = this?.isMicrophoneMuted == true,
)

/** What the notification shows, each time it changes after the one already posted. */
internal fun Flow<ElementCallSnapshot?>.notificationContentChanges(): Flow<NotificationContent> =
    filterNotNull()
        .map { it.notificationContent() }
        .distinctUntilChanged()
        .drop(1)
