/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.consumer

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import io.element.android.call.api.ElementCallController
import io.element.android.call.impl.ElementCallPictureInPicture
import io.element.android.call.impl.ElementCallStack
import io.element.android.call.impl.rtc.MatrixRtcNative
import io.element.android.call.test.FakeMatrixBackend
import io.element.android.call.ui.ElementCallActivity
import io.element.android.call.ui.theme.ElementCallStyle
import kotlinx.coroutines.MainScope

/** The host's half of the call screen, called the way a host calls it. */
class ConsumerApplication : Application(), ElementCallActivity.Host {
    lateinit var stack: ElementCallStack
        private set

    override fun onCreate() {
        super.onCreate()
        // What every host does before the first tile: load the core, whose JNI half the renderer needs.
        MatrixRtcNative.ensureInitialized()
        // The real stack, app-scoped, over the test transport so no SDK client is needed.
        stack = ElementCallStack.Builder(this, FakeMatrixBackend()).build(MainScope())
    }

    override val elementCallController: ElementCallController get() = stack.controller

    override fun leaveCallScreenIntent(context: Context): Intent =
        Intent(context, ConsumerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override fun onCallActivityCreated(activity: ComponentActivity, controller: ElementCallController) {
        ElementCallPictureInPicture.attach(activity, controller)
    }

    @Composable
    override fun CallScreenContent(content: @Composable (style: ElementCallStyle?) -> Unit) = content(null)
}
