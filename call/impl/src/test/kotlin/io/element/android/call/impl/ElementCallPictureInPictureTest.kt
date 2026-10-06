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
import android.graphics.Rect
import android.os.Looper
import android.util.Rational
import androidx.activity.ComponentActivity
import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.ElementCallWindowRect
import io.element.android.call.test.A_ROOM_ID
import io.element.android.call.test.FakeElementCallController
import io.element.android.call.tests.testutils.robolectric.RobolectricTest
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

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

    /** The window takes the tile's shape and grows out of it, rather than the app icon and a squeezed full screen. */
    @Test
    fun `a tile on screen gives the window its shape and its way in`() {
        // Robolectric's window is 320 wide.
        val activity = anActivityWithPictureInPicture()
        val tile = ElementCallWindowRect(left = 0, top = 20, right = 320, bottom = 260)

        val params = ElementCallPictureInPicture.pictureInPictureParams(activity, shouldEnter = true, isMuted = false, source = tile)

        assertThat(params.aspectRatio).isEqualTo(Rational(4, 3))
        assertThat(params.sourceRectHint).isEqualTo(Rect(0, 20, 320, 260))
    }

    /**
     * Android 14 ignores a hint smaller than the window it lands in, and a grid tile is about the
     * window's size: the hint is the tile grown to fill the screen, centred on it within the edges.
     */
    @Test
    fun `a small tile grows into a hint the window can shrink from`() {
        val activity = anActivityWithPictureInPicture()
        val tile = ElementCallWindowRect(left = 10, top = 100, right = 170, bottom = 220)

        val params = ElementCallPictureInPicture.pictureInPictureParams(activity, shouldEnter = true, isMuted = false, source = tile)

        assertThat(params.aspectRatio).isEqualTo(Rational(4, 3))
        assertThat(params.sourceRectHint).isEqualTo(Rect(0, 40, 320, 280))
    }

    /** The system refuses params outside its range, which would leave the window with none of them. */
    @Test
    fun `a tile too tall for a window is clamped and gives no way in`() {
        val activity = anActivityWithPictureInPicture()
        val tile = ElementCallWindowRect(left = 0, top = 0, right = 100, bottom = 400)

        val params = ElementCallPictureInPicture.pictureInPictureParams(activity, shouldEnter = true, isMuted = false, source = tile)

        assertThat(params.aspectRatio).isEqualTo(Rational(100, 239))
        assertThat(params.sourceRectHint).isNull()
    }

    @Test
    fun `a tile partly off screen gives the shape but no way in`() {
        val activity = anActivityWithPictureInPicture()
        val tile = ElementCallWindowRect(left = 0, top = -50, right = 320, bottom = 190)

        val params = ElementCallPictureInPicture.pictureInPictureParams(activity, shouldEnter = true, isMuted = false, source = tile)

        assertThat(params.aspectRatio).isEqualTo(Rational(4, 3))
        assertThat(params.sourceRectHint).isNull()
    }

    @Test
    fun `no call ignores a leftover tile`() {
        val activity = anActivityWithPictureInPicture()
        val tile = ElementCallWindowRect(left = 10, top = 20, right = 330, bottom = 260)

        val params = ElementCallPictureInPicture.pictureInPictureParams(activity, shouldEnter = false, isMuted = null, source = tile)

        assertThat(params.aspectRatio).isNull()
        assertThat(params.sourceRectHint).isNull()
    }

    /**
     * What Android 15's notice that the window is coming does (`PictureInPictureUiState` cannot be built
     * outside the platform, so the notice itself is not sent here): the screen draws the window's
     * content where the way in starts.
     */
    @Test
    fun `the window coming puts its content where the way in starts`() {
        val controller = aControllerLeavingForTheWindow()
        val activity = anActivityWithPictureInPicture()

        ElementCallPictureInPicture.showEntry(activity, controller)

        assertThat(controller.pictureInPictureEntry.value).isEqualTo(ElementCallWindowRect(left = 0, top = 40, right = 320, bottom = 280))
    }

    @Test
    fun `the entry goes once in the window`() {
        val controller = aControllerLeavingForTheWindow()
        val activity = anActivityWithPictureInPicture()
        ElementCallPictureInPicture.attach(activity, controller)
        ElementCallPictureInPicture.showEntry(activity, controller)

        activity.onPictureInPictureModeChanged(true, Configuration())

        assertThat(controller.isInPictureInPicture.value).isTrue()
        assertThat(controller.pictureInPictureEntry.value).isNull()
    }

    /** A way out that ended somewhere else - a dialog of ours - must not leave the window's content over the call. */
    @Test
    fun `the entry goes on coming back without a window`() {
        val controller = aControllerLeavingForTheWindow()
        val controllerActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controllerActivity.get()
        shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        ElementCallPictureInPicture.attach(activity, controller)
        ElementCallPictureInPicture.showEntry(activity, controller)

        controllerActivity.pause().resume()

        assertThat(controller.pictureInPictureEntry.value).isNull()
    }

    /** Android 14 has no notice: leaving is the closest it comes. */
    @Test
    @Config(sdk = [34])
    fun `leaving on Android 14 puts the window's content where the way in starts`() {
        val controller = aControllerLeavingForTheWindow()
        val activity = anActivityWithPictureInPicture()

        ElementCallPictureInPicture.onUserLeaveHint(activity, controller)

        assertThat(controller.pictureInPictureEntry.value).isEqualTo(ElementCallWindowRect(left = 0, top = 40, right = 320, bottom = 280))
    }

    /** From Android 15 the notice does it, without the false alarms of leaving for a dialog. */
    @Test
    fun `leaving on Android 15 waits for the notice`() {
        val controller = aControllerLeavingForTheWindow()
        val activity = anActivityWithPictureInPicture()

        ElementCallPictureInPicture.onUserLeaveHint(activity, controller)

        assertThat(controller.pictureInPictureEntry.value).isNull()
    }

    @Test
    @Config(sdk = [34])
    fun `leaving with no window to go to draws nothing`() {
        val controller = aControllerLeavingForTheWindow().apply { shouldEnterPictureInPicture.value = false }
        val activity = anActivityWithPictureInPicture()

        ElementCallPictureInPicture.onUserLeaveHint(activity, controller)

        assertThat(controller.pictureInPictureEntry.value).isNull()
    }

    /** A call whose grid tile, half the 320-wide Robolectric window, is what the window will show. */
    private fun aControllerLeavingForTheWindow() = FakeElementCallController(initialState = aCall()).apply {
        shouldEnterPictureInPicture.value = true
        pictureInPictureSource.value = ElementCallWindowRect(left = 10, top = 100, right = 170, bottom = 220)
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
