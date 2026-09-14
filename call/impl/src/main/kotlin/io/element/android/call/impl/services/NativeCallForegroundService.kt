/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl.services

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
import androidx.core.app.PendingIntentCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dev.zacsweers.metro.Inject
import io.element.android.features.callnative.impl.NativeCallController
import io.element.android.features.callnative.impl.di.NativeCallBindings
import io.element.android.features.callnative.impl.receivers.NativeCallActionReceiver
import io.element.android.libraries.architecture.bindings
import io.element.android.libraries.designsystem.icons.CompoundDrawables
import io.element.android.libraries.push.api.notifications.ForegroundServiceType
import io.element.android.libraries.push.api.notifications.NotificationIdProvider
import io.element.android.libraries.ui.strings.CommonStrings
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
 */
class NativeCallForegroundService : Service() {
    @Inject
    lateinit var controller: NativeCallController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        bindings<NativeCallBindings>().inject(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isProjecting = intent?.getBooleanExtra(EXTRA_IS_PROJECTING, false) == true
        createNotificationChannel()
        val notification = buildNotification()

        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                grantedServiceTypes(this, isProjecting),
            )
            START_STICKY
        } catch (throwable: Throwable) {
            // Most likely the microphone permission was revoked mid-call, which makes a microphone
            // foreground service illegal to start.
            Timber.w(throwable, "NativeCall: cannot start foreground service")
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
     * Read from the controller at post time. The service is restarted whenever the call's shape
     * changes - a permission granted, a screen share started - so the title follows the call rather
     * than being frozen at whatever it was when the call began.
     */
    private fun buildNotification(): Notification {
        val call = controller.state.value
        val roomName = call?.roomName?.takeIf { it.isNotBlank() }
            ?: getString(CommonStrings.common_call_in_progress)
        // CallStyle needs somebody to name the call after, and for a room that is the room.
        val caller = Person.Builder().setName(roomName).setImportant(true).build()

        val hangUpIntent = broadcast(NativeCallActionReceiver.ACTION_HANG_UP, HANG_UP_REQUEST_CODE)
        val isMuted = call?.isMicrophoneMuted == true
        val muteIntent = broadcast(NativeCallActionReceiver.ACTION_TOGGLE_MUTE, TOGGLE_MUTE_REQUEST_CODE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(caller, hangUpIntent))
            .addPerson(caller)
            // Tapping it comes back to the call rather than doing nothing, which is what the first
            // version of this did. Straight to MainActivity because that is where the call is drawn -
            // there is no call Activity to return to, which is the whole point of the architecture.
            .setContentIntent(returnToCallIntent())
            .addAction(
                NotificationCompat.Action.Builder(
                    if (isMuted) CompoundDrawables.ic_compound_mic_off_solid else CompoundDrawables.ic_compound_mic_on_solid,
                    getString(if (isMuted) CommonStrings.a11y_unmute_microphone else CommonStrings.a11y_mute_microphone),
                    muteIntent,
                ).build()
            )
            .build()
    }

    /**
     * Back into the running call.
     *
     * `MainActivity` is `singleTask`, so this brings the existing task forward rather than building a
     * second one - the call is already drawn inside it and simply becomes visible again.
     */
    private fun returnToCallIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: return null
        return PendingIntentCompat.getActivity(this, RETURN_REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT, false)
    }

    private fun broadcast(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, NativeCallActionReceiver::class.java).setAction(action)
        // Explicitly not immutable: mutability is irrelevant here since the receiver reads no extras,
        // but PendingIntentCompat wants the choice made rather than defaulted.
        return PendingIntentCompat.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT, false)!!
    }

    private fun createNotificationChannel() {
        // Channels arrived in O, and the app still supports 24 (Versions.MIN_SDK_FOSS). Touching
        // NotificationChannel below that throws before the service can start its own notification.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Native call",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "native_call_foreground_service_channel"

        /**
         * From the shared provider rather than the hardcoded 4242 this used to be.
         * [ForegroundServiceType.ONGOING_CALL] is the WebView call's id too, which is right: only one
         * call runs at a time and the two paths are mutually exclusive, so they must not be able to
         * leave two ongoing-call notifications in the shade.
         */
        private val NOTIFICATION_ID = NotificationIdProvider.getForegroundServiceNotificationId(ForegroundServiceType.ONGOING_CALL)

        private const val EXTRA_IS_PROJECTING = "is_projecting"
        private const val HANG_UP_REQUEST_CODE = 0
        private const val TOGGLE_MUTE_REQUEST_CODE = 1
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
                Timber.w("NativeCall: not starting foreground service, no microphone permission")
                return
            }
            val intent = Intent(context, NativeCallForegroundService::class.java)
                .putExtra(EXTRA_IS_PROJECTING, isProjecting)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NativeCallForegroundService::class.java))
        }
    }
}
