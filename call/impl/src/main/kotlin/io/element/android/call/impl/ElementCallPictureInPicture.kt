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
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.element.android.call.api.ElementCallController
import io.element.android.call.api.ElementCallWindowRect
import io.element.android.call.impl.receivers.ElementCallActionIntents
import io.element.android.call.impl.util.runCatchingExceptions
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Follows a full-screen call out of the app as a floating window, from the host's Activity.
 *
 * The library has no Activity of its own (plan decision 1): the call is drawn inside the host's, so the
 * host's is what shrinks. These are the lines every host would otherwise write itself. The manifest half
 * stays with the host - `android:supportsPictureInPicture="true"` and `smallestScreenSize` in
 * `configChanges` on the Activity - because a library cannot change a host Activity's manifest entry.
 *
 * Two mechanisms, because auto-enter only arrived in Android 12. From S the system is *told* the Activity
 * would like to shrink and does it itself on any way out - the home gesture included, which is the one
 * people actually use and the one `onUserLeaveHint` never sees. Below S the hint is all there is, so it
 * covers Home and Recents and not gestures.
 *
 * Kept in step with the call rather than set once: the params are re-applied whenever the call appears, is
 * maximized, is minimized or ends, so backgrounding the app with no call - or with a call docked in the
 * bar - behaves exactly as it did before any of this existed. See
 * [ElementCallController.shouldEnterPictureInPicture] for the rule.
 *
 * The window carries mute and hang up as system actions, sent to the same receiver as the notification's.
 *
 * A call ending while floating closes the window by moving the task back, not by finishing the host's Activity.
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
                    combine(
                        controller.shouldEnterPictureInPicture,
                        controller.state.map { it?.isMicrophoneMuted },
                        controller.pictureInPictureSource,
                        ::Triple,
                    )
                        .distinctUntilChanged()
                        .collect { (shouldEnter, isMuted, source) ->
                            runCatchingExceptions {
                                activity.setPictureInPictureParams(pictureInPictureParams(activity, shouldEnter, isMuted, source))
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
            // In this order, so the frame that drops the entry is already the window's content.
            controller.setInPictureInPicture(info.isInPictureInPictureMode)
            controller.setPictureInPictureEntry(null)
        }
        // Android 15 says the window is coming before the system shows its way in: time to draw the
        // window's content where the way in starts from.
        activity.addOnPictureInPictureUiStateChangedListener { uiState ->
            if (uiState.isTransitioningToPip) showEntry(activity, controller)
        }
        // A way out that did not end in the window - a dialog of our own, Recents left again - must
        // not keep the window's content over the call.
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) controller.setPictureInPictureEntry(null)
            }
        )
    }

    /**
     * Call from the Activity's `onUserLeaveHint()` override.
     *
     * Before S, the path into picture-in-picture. From S, `setAutoEnterEnabled` already covers every way
     * out of the app, and entering here as well would do it twice; on S to U this only draws the window's
     * content where the way in starts from, the closest those versions come to Android 15's notice
     * that the window is coming. It can lose the race with the system's own animation, and it is also
     * called for ways out that end in no window (another Activity of ours), which the next resume undoes.
     */
    fun onUserLeaveHint(activity: Activity, controller: ElementCallController) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM && activity.supportsPictureInPicture()) {
                showEntry(activity, controller)
            }
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!controller.shouldEnterPictureInPicture.value) return
        if (!activity.supportsPictureInPicture()) return
        val params = pictureInPictureParams(
            activity = activity,
            shouldEnter = true,
            isMuted = controller.state.value?.isMicrophoneMuted,
            source = controller.pictureInPictureSource.value,
        )
        runCatchingExceptions { activity.enterPictureInPictureMode(params) }
            .onFailure { Timber.w(it, "ElementCall: cannot enter picture-in-picture") }
    }

    /**
     * Have the screen draw the window's content over the rectangle the system is about to shrink, so
     * the way in shows what the window will, not the layout around the tile.
     */
    internal fun showEntry(activity: Activity, controller: ElementCallController) {
        if (!controller.shouldEnterPictureInPicture.value || controller.isInPictureInPicture.value) return
        val tile = controller.pictureInPictureSource.value ?: return
        val entry = sourceRectHint(activity, tile) ?: return
        Timber.d("ElementCall: drawing the picture-in-picture content at $entry on the way in")
        controller.setPictureInPictureEntry(entry)
    }

    /**
     * [isMuted] is null without a call, and then the window carries no actions.
     *
     * [source] is where the tile the window will show is drawn. Without it the system knows neither
     * the window's shape nor what in the screen becomes the window, so it covers the way in with the
     * app's icon and then squeezes the last full-screen frame into a window of its default shape
     * until the app draws again. With it the window has the tile's shape, and the system crops the
     * screen down onto the tile, which is already what the window will show.
     *
     * The hint is the tile grown to fill the screen, not the tile: Android 14 ignores a hint smaller
     * than the window it lands in (it will not scale up), and a grid tile is about half the screen's
     * width, the size the window opens at on a phone.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    internal fun pictureInPictureParams(
        activity: Activity,
        shouldEnter: Boolean,
        isMuted: Boolean?,
        source: ElementCallWindowRect? = null,
    ): PictureInPictureParams {
        val actions = if (isMuted == null) emptyList() else listOf(microphoneAction(activity, isMuted), hangUpAction(activity))
        val tile = source?.takeIf { isMuted != null && it.width > 0 && it.height > 0 }
        return PictureInPictureParams.Builder()
            .setActions(actions.take(activity.maxNumPictureInPictureActions))
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(shouldEnter) }
            .apply {
                if (tile != null) {
                    setAspectRatio(tile.aspectRatio())
                    sourceRectHint(activity, tile)?.let { setSourceRectHint(Rect(it.left, it.top, it.right, it.bottom)) }
                }
            }
            .build()
    }

    /**
     * What the system shrinks into the window: the tile grown to the largest rectangle of its shape
     * that fits the window, centred on it as far as the window's edges allow. Null for a tile not of a
     * shape a window can take, or not all on screen: the system ignores such a hint and falls back to
     * the icon, which is no worse than none.
     */
    internal fun sourceRectHint(activity: Activity, tile: ElementCallWindowRect): ElementCallWindowRect? {
        val window = activity.windowSize() ?: return null
        if (tile.width <= 0 || tile.height <= 0) return null
        if (tile.aspectRatio() != Rational(tile.width, tile.height) || !tile.isWithin(window)) return null
        val scale = minOf(window.width.toFloat() / tile.width, window.height.toFloat() / tile.height)
        val width = (tile.width * scale).roundToInt().coerceAtMost(window.width)
        val height = (tile.height * scale).roundToInt().coerceAtMost(window.height)
        val left = ((tile.left + tile.right - width) / 2).coerceIn(0, window.width - width)
        val top = ((tile.top + tile.bottom - height) / 2).coerceIn(0, window.height - height)
        return ElementCallWindowRect(left = left, top = top, right = left + width, bottom = top + height)
    }

    /** The tile's shape, within what the system accepts for a window (it refuses the params otherwise). */
    private fun ElementCallWindowRect.aspectRatio(): Rational {
        val ratio = Rational(width, height)
        return when {
            ratio > MAX_ASPECT_RATIO -> MAX_ASPECT_RATIO
            ratio < MIN_ASPECT_RATIO -> MIN_ASPECT_RATIO
            else -> ratio
        }
    }

    private fun Activity.windowSize(): Size? =
        window?.decorView?.let { Size(it.width, it.height) }?.takeIf { it.width > 0 && it.height > 0 }

    private fun ElementCallWindowRect.isWithin(window: Size): Boolean =
        left >= 0 && top >= 0 && right <= window.width && bottom <= window.height

    // android.R.dimen.config_pictureInPictureMin/MaxAspectRatio's defaults, which the platform does not expose.
    private val MAX_ASPECT_RATIO = Rational(239, 100)
    private val MIN_ASPECT_RATIO = Rational(100, 239)

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
