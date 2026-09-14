/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl

import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import com.google.common.truth.Truth.assertThat
import io.element.android.call.test.FakeElementCallController
import io.element.android.call.tests.testutils.robolectric.RobolectricTest
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

class ElementCallPictureInPictureTest : RobolectricTest() {
    /**
     * The half of the binder that has to work on every Android version: the controller learns about the
     * window shrinking and growing, which is what puts the call back full screen on the way out.
     */
    @Test
    fun `the controller is told when the activity enters and leaves picture-in-picture`() {
        val controller = FakeElementCallController()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)

        ElementCallPictureInPicture.attach(activity, controller)

        activity.onPictureInPictureModeChanged(true, Configuration())
        assertThat(controller.isInPictureInPicture.value).isTrue()

        activity.onPictureInPictureModeChanged(false, Configuration())
        assertThat(controller.isInPictureInPicture.value).isFalse()
    }

    /** A device without the feature - a TV box, a watch - must be left exactly as it was. */
    @Test
    fun `a device without picture-in-picture is left alone`() {
        val controller = FakeElementCallController()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, false)

        ElementCallPictureInPicture.attach(activity, controller)

        activity.onPictureInPictureModeChanged(true, Configuration())
        assertThat(controller.isInPictureInPicture.value).isFalse()
    }
}
