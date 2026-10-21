/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import io.element.android.call.api.ElementCallController
import io.element.android.call.impl.ElementCallPictureInPicture
import io.element.android.call.impl.rtc.MatrixRtcNative
import io.element.android.call.ui.ElementCallActivity
import io.element.android.call.ui.theme.ElementCallStyle
import kotlinx.coroutines.MainScope
import timber.log.Timber

/** The host's half of the call screen, as a host's `Application` carries it. */
class SampleApplication : Application(), ElementCallActivity.Host {
    lateinit var harness: SampleHarness
        private set

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        // The video renderer wraps frames in libwebrtc's buffers, whose JNI half lives in the RTC
        // library. It has to be loaded before the first tile draws, even though no call is ever joined.
        MatrixRtcNative.ensureInitialized()
        harness = SampleHarness(context = this, scope = MainScope())
        registerActivityLifecycleCallbacks(ResumedActivityTracker())
    }

    override val elementCallController: ElementCallController get() = harness.controller

    override fun leaveCallScreenIntent(context: Context): Intent =
        Intent(context, SampleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    override fun onCallActivityCreated(activity: ComponentActivity, controller: ElementCallController) {
        ElementCallPictureInPicture.attach(activity, controller)
    }

    @Composable
    override fun CallScreenContent(content: @Composable (style: ElementCallStyle?) -> Unit) {
        SampleCallScreen(harness = harness, content = content)
    }

    private inner class ResumedActivityTracker : ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            harness.resumedActivity = activity
        }

        override fun onActivityPaused(activity: Activity) {
            if (harness.resumedActivity === activity) harness.resumedActivity = null
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
