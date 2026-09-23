/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Looper
import androidx.activity.ComponentActivity
import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.test.A_ROOM_ID
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

    @Test
    fun `the window closes when the call ends in picture-in-picture`() {
        val controller = FakeElementCallController(initialState = aCall())
        val activity = anActivityWithPictureInPicture()
        ElementCallPictureInPicture.attach(activity, controller)
        activity.enterPictureInPictureMode(PictureInPictureParams.Builder().build())
        shadowOf(Looper.getMainLooper()).idle()

        controller.state.value = null
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(shadowOf(activity).isTaskMovedToBack).isTrue()
    }

    @Test
    fun `ending a call outside picture-in-picture leaves the task alone`() {
        val controller = FakeElementCallController(initialState = aCall())
        val activity = anActivityWithPictureInPicture()
        ElementCallPictureInPicture.attach(activity, controller)
        shadowOf(Looper.getMainLooper()).idle()

        controller.state.value = null
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(shadowOf(activity).isTaskMovedToBack).isFalse()
    }

    @Test
    fun `a call puts mute then hang up on the window`() {
        val activity = anActivityWithPictureInPicture()

        val params = ElementCallPictureInPicture.pictureInPictureParams(activity, shouldEnter = true, isMuted = false)

        assertThat(params.actions.map { it.title }).containsExactly("Mute", "Hang up").inOrder()
    }

    @Test
    fun `a muted call offers unmute`() {
        val activity = anActivityWithPictureInPicture()

        val params = ElementCallPictureInPicture.pictureInPictureParams(activity, shouldEnter = true, isMuted = true)

        assertThat(params.actions.first().title).isEqualTo("Unmute")
    }

    @Test
    fun `no call leaves the window without actions`() {
        val activity = anActivityWithPictureInPicture()

        val params = ElementCallPictureInPicture.pictureInPictureParams(activity, shouldEnter = false, isMuted = null)

        assertThat(params.actions).isEmpty()
    }

    private fun anActivityWithPictureInPicture(): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        return activity
    }

    private fun aCall() = ElementCallSnapshot(
        callData = ElementCallData(roomId = A_ROOM_ID, isAudioCall = false),
        connection = ElementCallConnection.Connected,
    )
}
