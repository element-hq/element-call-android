/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.callnative.impl.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.zacsweers.metro.Inject
import io.element.android.features.callnative.impl.NativeCallController
import io.element.android.features.callnative.impl.di.NativeCallBindings
import io.element.android.libraries.architecture.bindings
import timber.log.Timber

/**
 * Acts on the ongoing-call notification's buttons.
 *
 * A receiver rather than an Activity because these must not open anything: hanging up from the
 * notification shade should end the call and leave the shade where it is, and bringing the app to the
 * front to do it would be the opposite of why someone used the notification.
 *
 * Everything it needs is on [NativeCallController], which is `@SingleIn(AppScope::class)` - so this
 * reaches the very same running call the UI is bound to, and the state change is picked up by both.
 */
class NativeCallActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var controller: NativeCallController

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        context.bindings<NativeCallBindings>().inject(this)
        when (action) {
            ACTION_HANG_UP -> {
                Timber.i("NativeCall: hang up from the notification")
                controller.hangUp()
            }
            ACTION_TOGGLE_MUTE -> {
                // Read from the controller rather than carried in the intent: a PendingIntent built
                // when the notification was posted would hold whatever the state was *then*, and
                // muting from the call screen afterwards would leave this button inverted.
                val isMuted = controller.state.value?.isMicrophoneMuted == true
                Timber.i("NativeCall: ${if (isMuted) "unmute" else "mute"} from the notification")
                controller.setMicrophoneMuted(!isMuted)
            }
            else -> Timber.w("NativeCall: unknown notification action $action")
        }
    }

    companion object {
        const val ACTION_HANG_UP = "io.element.android.callnative.HANG_UP"
        const val ACTION_TOGGLE_MUTE = "io.element.android.callnative.TOGGLE_MUTE"
    }
}
