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
import android.view.Choreographer
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.PendingIntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import io.element.android.call.api.ElementCallController
import io.element.android.call.ui.theme.ElementCallStyle
import io.element.android.call.ui.theme.ElementCallTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * The full-screen call, on top of the host's Activity in the host's task.
 *
 * Its own Activity so that it alone can show over the lock screen: answering a call on a locked phone
 * must not expose the host's Activity, nor skip the host's own lock. Leaving it for the app asks the
 * keyguard first, then goes back to the host's Activity under it, which applies the host's lock; with
 * none under it, after a cold start, it starts [Host.leaveCallScreenIntent].
 *
 * Minimizing a video call shrinks this Activity into picture-in-picture, which the system moves to a task
 * of its own, leaving the host's Activity under the window; an audio call, or one the system refuses
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

    // The window's own mode, not the controller's flag, which is set as soon as the window is asked for: the
    // screen must keep drawing the call until the system has taken its picture to shrink.
    private var isFloating by mutableStateOf(false)

    // Home from Android 12: the system shrinks the screen without waiting, so the chrome goes at once instead.
    private var isLeavingForPictureInPicture by mutableStateOf(false)
    private var pictureInPictureShown: CompletableDeferred<Unit>? = null

    /** Minimized into picture-in-picture by this screen with nothing of the app under it, which then follows. */
    private var isLeavingForAppInPictureInPicture = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = application as? Host
        val controller = host?.elementCallController
        if (host == null || controller?.state?.value == null) {
            Timber.w("ElementCall: call screen opened with ${if (host == null) "no host" else "no call"}")
            close()
            return
        }
        this.host = host
        this.controller = controller
        enableEdgeToEdge()
        showWhenLocked()
        host.onCallActivityCreated(this, controller)
        addOnPictureInPictureModeChangedListener { info -> onPictureInPictureChanged(info.isInPictureInPictureMode) }
        addOnUserLeaveHintListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && controller.shouldEnterPictureInPicture.value) isLeavingForPictureInPicture = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            addOnPictureInPictureUiStateChangedListener { if (it.isTransitioningToPip) isLeavingForPictureInPicture = true }
        }
        // A way out that ended somewhere else, a dialog or Recents left again, brings the chrome back.
        lifecycle.addObserver(LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) isLeavingForPictureInPicture = false })
        lifecycleScope.launch {
            controller.state.first { it == null }
            Timber.d("ElementCall: call over, closing the call screen")
            forgetCallSpotlightMemory(controller)
            close()
        }
        lifecycleScope.launch {
            // Minimized by something other than this screen, the notification or a host, say.
            controller.state.first { it != null && !it.isMaximized }
            if (!isFinishing) leaveForApp()
        }
        setContent {
            host.CallScreenContent { style ->
                ElementCallTheme(style = style) {
                    ElementCallWindow(
                        controller = controller,
                        isInPictureInPicture = isFloating,
                        onMinimize = ::minimize,
                        onClose = ::close,
                        isLeavingForPictureInPicture = isLeavingForPictureInPicture,
                        onPictureInPictureShow = { pictureInPictureShown?.complete(Unit) },
                    )
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
            close()
        }
    }

    private fun minimizeUnlocked() {
        val call = controller.state.value ?: return close()
        if (!call.hasVideo || !canEnterPictureInPicture()) {
            controller.setMaximized(false)
            leaveForApp()
            return
        }
        // The window's content drawn first where the system shrinks from, and only once it shows: what shrinks
        // is then what the window will show, rather than the layout around the tile or a black renderer.
        val decorView = window.decorView
        val entry = controller.pictureInPictureSource.value?.grownToFill(decorView.width, decorView.height)
        val shown = CompletableDeferred<Unit>().takeIf { entry != null }
        pictureInPictureShown = shown
        entry?.let(controller::setPictureInPictureEntry)
        lifecycleScope.launch {
            if (shown != null) {
                // Bounded, so a stream that stalls delays minimising rather than blocking it.
                withTimeoutOrNull(PICTURE_IN_PICTURE_SHOWN_TIMEOUT_MS) { shown.await() }
                // The frame reached the view; then a frame for the layer to be shown, and one for it to reach the screen.
                repeat(2) { awaitFrame() }
            }
            if (enterPictureInPicture()) {
                // Now rather than on the mode change, which comes after the host's Activity has resumed under
                // the window: its overlay would see a maximized call out of picture-in-picture and open it again.
                controller.setInPictureInPicture(true)
                // On top of the app's Activity, the window floats over it. Opened with nothing of the app under it,
                // a notification answered from a cold start, the app follows once the window floats: started now, it
                // would close this task in the same transition.
                isLeavingForAppInPictureInPicture = isTaskRoot
            } else {
                controller.setPictureInPictureEntry(null)
                controller.setMaximized(false)
                leaveForApp()
            }
        }
    }

    private fun canEnterPictureInPicture(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun enterPictureInPicture(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        // False, or a throw on some versions, when the user turned picture-in-picture off for the app.
        return try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        } catch (e: IllegalStateException) {
            Timber.w(e, "ElementCall: picture-in-picture refused")
            false
        }
    }

    private fun onPictureInPictureChanged(isInPictureInPicture: Boolean) {
        isFloating = isInPictureInPicture
        isLeavingForPictureInPicture = false
        pictureInPictureShown = null
        controller.setInPictureInPicture(isInPictureInPicture)
        if (isInPictureInPicture && isLeavingForAppInPictureInPicture) {
            isLeavingForAppInPictureInPicture = false
            startActivity(host.leaveCallScreenIntent(this))
        }
        if (!isInPictureInPicture && lifecycle.currentState == Lifecycle.State.CREATED) {
            // Out of picture-in-picture while stopped: the window was swiped away, not expanded.
            Timber.d("ElementCall: picture-in-picture dismissed, the call carries on minimized")
            controller.setMaximized(false)
            close()
        }
    }

    /** Back to the app's Activity this was opened over, or the whole task when this is all there is of it. */
    private fun close() {
        if (isTaskRoot) finishAndRemoveTask() else finish()
    }

    private fun leaveForApp() {
        // Disarmed here rather than left to the binder, which follows the call a dispatch later: leaving with
        // auto-enter still on would shrink this task into a window on its way out.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        }
        if (isTaskRoot) startActivity(host.leaveCallScreenIntent(this))
        close()
    }

    private suspend fun awaitFrame() = suspendCancellableCoroutine { continuation ->
        val callback = Choreographer.FrameCallback { continuation.resume(Unit) }
        Choreographer.getInstance().postFrameCallback(callback)
        continuation.invokeOnCancellation { Choreographer.getInstance().removeFrameCallback(callback) }
    }

    companion object {
        private const val PICTURE_IN_PICTURE_SHOWN_TIMEOUT_MS = 500L

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
