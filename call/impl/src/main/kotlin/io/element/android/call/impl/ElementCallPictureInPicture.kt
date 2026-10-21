/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl

import android.app.Activity
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.element.android.call.api.ElementCallController
import io.element.android.call.impl.receivers.ElementCallActionIntents
import io.element.android.call.impl.util.runCatchingExceptions
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Follows a full-screen call out of the app as a floating window, from `ElementCallActivity`. The host
 * calls [attach] from `ElementCallActivity.Host.onCallActivityCreated`.
 *
 * From S the system is *told* the Activity would like to shrink and does it itself on any way out, the
 * home gesture included. Below S, `ElementCallActivity` enters on `onUserLeaveHint` itself.
 *
 * Kept in step with the call rather than set once: the params are re-applied whenever the call appears, is
 * maximized, is minimized or ends. See [ElementCallController.shouldEnterPictureInPicture] for the rule.
 *
 * The window carries mute and hang up as system actions, sent to the same receiver as the notification's.
 *
 * A call ending while floating closes the window by moving the task back.
 */
object ElementCallPictureInPicture {
    /**
     * Install both halves on [activity]: the auto-enter params that follow the call, and the listener that
     * tells [controller] when the window shrinks and grows. Call once, from `onCreate`.
     */
    fun attach(activity: ComponentActivity, controller: ElementCallController) {
        if (!activity.supportsPictureInPicture()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.lifecycleScope.launch {
                activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    combine(controller.shouldEnterPictureInPicture, controller.state.map { it?.isMicrophoneMuted }, ::Pair)
                        .distinctUntilChanged()
                        .collect { (shouldEnter, isMuted) ->
                            runCatchingExceptions {
                                activity.setPictureInPictureParams(pictureInPictureParams(activity, shouldEnter, isMuted))
                            }.onFailure { Timber.w(it, "ElementCall: cannot set picture-in-picture params") }
                        }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.lifecycleScope.launch {
                activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    controller.state
                        .map { it != null }
                        .distinctUntilChanged()
                        // Transitions only: a host may use picture-in-picture without a call.
                        .drop(1)
                        .filter { hasCall -> !hasCall }
                        .collect {
                            if (activity.isInPictureInPictureMode) {
                                Timber.d("ElementCall: call ended in picture-in-picture")
                                activity.moveTaskToBack(false)
                            }
                        }
                }
            }
        }
        activity.addOnPictureInPictureModeChangedListener { info ->
            Timber.d("ElementCall: in picture-in-picture: ${info.isInPictureInPictureMode}")
            controller.setInPictureInPicture(info.isInPictureInPictureMode)
        }
    }

    /** [isMuted] is null without a call, and then the window carries no actions. */
    @RequiresApi(Build.VERSION_CODES.O)
    internal fun pictureInPictureParams(activity: Activity, shouldEnter: Boolean, isMuted: Boolean?): PictureInPictureParams {
        val actions = if (isMuted == null) emptyList() else listOf(microphoneAction(activity, isMuted), hangUpAction(activity))
        return PictureInPictureParams.Builder()
            .setActions(actions.take(activity.maxNumPictureInPictureActions))
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(shouldEnter) }
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun microphoneAction(context: Context, isMuted: Boolean): RemoteAction {
        val title = context.getString(if (isMuted) R.string.element_call_notification_unmute_microphone else R.string.element_call_notification_mute_microphone)
        val icon = if (isMuted) R.drawable.ic_element_call_notification_mic_off else R.drawable.ic_element_call_notification_mic_on
        return RemoteAction(Icon.createWithResource(context, icon), title, title, ElementCallActionIntents.toggleMute(context))
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun hangUpAction(context: Context): RemoteAction {
        val title = context.getString(R.string.element_call_notification_hang_up)
        val icon = Icon.createWithResource(context, R.drawable.ic_element_call_notification_end_call)
        return RemoteAction(icon, title, title, ElementCallActionIntents.hangUp(context))
    }

    private fun Activity.supportsPictureInPicture(): Boolean =
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
}
