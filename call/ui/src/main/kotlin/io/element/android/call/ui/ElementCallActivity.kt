/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.core.app.PendingIntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.element.android.call.api.ElementCallController
import io.element.android.call.ui.theme.ElementCallStyle
import io.element.android.call.ui.theme.ElementCallTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The full-screen call, in a task of its own.
 *
 * Its own Activity so that it alone can show over the lock screen: answering a call on a locked phone
 * must not expose the host's Activity, nor skip the host's own lock. Leaving it for the app asks the
 * keyguard first, then starts [Host.leaveCallScreenIntent], whose Activity applies the host's lock.
 *
 * Minimizing a video call shrinks this Activity into picture-in-picture over whatever task is underneath,
 * the host's when the call was opened from it; an audio call, or one the system refuses
 * picture-in-picture, minimizes to the host's [ElementCallOverlay] bar.
 *
 * The host's `Application` implements [Host].
 */
class ElementCallActivity : ComponentActivity() {
    /** What the call screen needs from the host, implemented by its `Application`. */
    interface Host {
        /** The running call, or null when there is none and the screen should close. */
        val elementCallController: ElementCallController?

        /** The host Activity the call screen leaves for, which shows the bar and applies the host's own lock. */
        fun leaveCallScreenIntent(context: Context): Intent

        /**
         * Called once from `onCreate`. Attach picture-in-picture here
         * (`ElementCallPictureInPicture.attach(activity, controller)`), and anything the host applies to
         * every Activity of its own, such as `FLAG_SECURE`.
         */
        fun onCallActivityCreated(activity: ComponentActivity, controller: ElementCallController)

        /** Wraps the call screen, giving it the host's [ElementCallStyle] and any composition local it needs. */
        @Composable
        fun CallScreenContent(content: @Composable (style: ElementCallStyle?) -> Unit)
    }

    private lateinit var controller: ElementCallController
    private lateinit var host: Host

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = application as? Host
        val controller = host?.elementCallController
        if (host == null || controller?.state?.value == null) {
            Timber.w("ElementCall: call screen opened with ${if (host == null) "no host" else "no call"}")
            finishAndRemoveTask()
            return
        }
        this.host = host
        this.controller = controller
        enableEdgeToEdge()
        showWhenLocked()
        host.onCallActivityCreated(this, controller)
        addOnPictureInPictureModeChangedListener { info -> onPictureInPictureChanged(info.isInPictureInPictureMode) }
        lifecycleScope.launch {
            controller.state.first { it == null }
            Timber.d("ElementCall: call over, closing the call screen")
            forgetCallSpotlightMemory(controller)
            finishAndRemoveTask()
        }
        lifecycleScope.launch {
            // Minimized by something other than this screen, the notification or a host, say.
            controller.state.first { it != null && !it.isMaximized }
            if (!isFinishing) leaveForApp()
        }
        setContent {
            host.CallScreenContent { style ->
                ElementCallTheme(style = style) {
                    ElementCallWindow(controller = controller, onMinimize = ::minimize, onClose = ::finishAndRemoveTask)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Destroyed while floating, the call ending in the window: no mode change follows, and the next call
        // would otherwise start "in picture-in-picture" and never be opened.
        if (::controller.isInitialized && controller.isInPictureInPicture.value && !isChangingConfigurations) {
            controller.setInPictureInPicture(false)
        }
    }

    /** Below S the system cannot be told to shrink on its own, and Home and Recents are all there is to go on. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || !::controller.isInitialized) return
        if (controller.shouldEnterPictureInPicture.value) enterPictureInPicture()
    }

    private fun showWhenLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
    }

    private fun minimize() {
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        if (keyguardManager?.isKeyguardLocked != true) {
            minimizeUnlocked()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keyguardManager.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = minimizeUnlocked()
                },
            )
        } else {
            // No way to ask for the keyguard: leave the call running behind it, the bar waits in the app.
            controller.setMaximized(false)
            finishAndRemoveTask()
        }
    }

    private fun minimizeUnlocked() {
        val call = controller.state.value ?: return finishAndRemoveTask()
        // Nothing is started for the app: the task under this one comes up under the window, the host's when
        // the call was opened from it. Starting it here would close this task in the same transition.
        if (call.hasVideo && enterPictureInPicture()) {
            // Now rather than on the mode change, which comes after the host's Activity has resumed under the
            // window: its overlay would see a maximized call out of picture-in-picture and open it again.
            controller.setInPictureInPicture(true)
            return
        }
        controller.setMaximized(false)
        leaveForApp()
    }

    private fun enterPictureInPicture(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        // False, or a throw on some versions, when the user turned picture-in-picture off for the app.
        return try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        } catch (e: IllegalStateException) {
            Timber.w(e, "ElementCall: picture-in-picture refused")
            false
        }
    }

    private fun onPictureInPictureChanged(isInPictureInPicture: Boolean) {
        controller.setInPictureInPicture(isInPictureInPicture)
        if (!isInPictureInPicture && lifecycle.currentState == Lifecycle.State.CREATED) {
            // Out of picture-in-picture while stopped: the window was swiped away, not expanded.
            Timber.d("ElementCall: picture-in-picture dismissed, the call carries on minimized")
            controller.setMaximized(false)
            finishAndRemoveTask()
        }
    }

    private fun leaveForApp() {
        // Disarmed here rather than left to the binder, which follows the call a dispatch later: leaving with
        // auto-enter still on would shrink this task into a window on its way out.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        }
        startActivity(host.leaveCallScreenIntent(this))
        finishAndRemoveTask()
    }

    companion object {
        /** Open the call screen, from anywhere: the answer from a ringing screen, the host, a notification. */
        fun start(context: Context, options: Bundle? = null) {
            context.startActivity(intent(context), options)
        }

        /** The call screen as a notification's content intent (`ElementCallNotificationConfig.contentIntent`). */
        fun pendingIntent(context: Context): PendingIntent =
            checkNotNull(PendingIntentCompat.getActivity(context, 0, intent(context), PendingIntent.FLAG_UPDATE_CURRENT, false))

        private fun intent(context: Context) = Intent(context, ElementCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
