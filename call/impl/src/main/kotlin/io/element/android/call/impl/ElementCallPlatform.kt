/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.SystemClock
import io.element.android.call.api.rtc.MatrixRtcReactionKind
import io.element.android.call.impl.audio.CallSoundEffectRenderer
import io.element.android.call.impl.audio.DefaultCallSoundEffectRenderer
import io.element.android.call.impl.rtc.coreReactionCatalogue
import io.element.android.call.impl.services.ElementCallForegroundService
import io.element.android.call.impl.util.runCatchingExceptions
import timber.log.Timber
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Android surface a running call needs, behind an interface so [DefaultElementCallController] can be
 * tested on the JVM.
 *
 * Both members are static platform calls that throw outside an instrumented environment - starting a
 * service needs a real `Context`, and `SystemClock` is one of the stubbed-out `android.jar` methods.
 * Neither is worth a Robolectric runner on tests that are otherwise about call logic.
 */
internal interface ElementCallPlatform {
    /**
     * Milliseconds since boot, monotonic.
     *
     * Not wall clock: a duration counted from this cannot jump when the system clock is corrected
     * mid-call, which is exactly when a call is most likely to be long enough for it to matter.
     */
    fun elapsedRealtimeMs(): Long

    /** Milliseconds since the Unix epoch: the clock the core stamps a call's moments with. */
    fun currentTimeMs(): Long

    /**
     * Start, or restart, the call foreground service.
     *
     * Restarting is how the service picks up a permission granted since it started - the camera, in
     * practice - so this is called again rather than only once.
     *
     * @param isProjecting whether to claim the `mediaProjection` service type as well. From Android
     * 14 the service must already be running with that type when the projection is claimed, so this
     * has to be called - and have returned - before the screen capture starts. Passed explicitly
     * rather than derived from a permission because there is no permission to derive it from: the
     * grant is a one-shot token from a system dialog, not something [android.content.Context] can be
     * asked about. It is also dropped again when sharing stops, so the system's screen-recording
     * indicator does not outlive the share.
     */
    fun startForegroundService(isProjecting: Boolean = false)

    fun stopForegroundService()

    /** Whether the device has a camera on each side, so there is anything to switch to (019 R24). */
    fun isCameraSwitchAvailable(): Boolean

    /** A renderer for one call's sound effects, released when it ends. */
    fun createCallSoundEffectRenderer(volume: Float): CallSoundEffectRenderer

    /** How long a received reaction waits before it appears, at random up to half a second (037 R2b). */
    fun reactionSpread(): Duration

    /** The core's reaction catalogue, empty if it cannot be read: native, so not on a JVM test. */
    fun reactionCatalogue(): List<MatrixRtcReactionKind>
}

internal class DefaultElementCallPlatform(
    private val context: Context,
) : ElementCallPlatform {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    override fun currentTimeMs(): Long = System.currentTimeMillis()

    override fun startForegroundService(isProjecting: Boolean) = ElementCallForegroundService.start(context, isProjecting)

    override fun stopForegroundService() = ElementCallForegroundService.stop(context)

    override fun isCameraSwitchAvailable(): Boolean {
        val manager = context.getSystemService(CameraManager::class.java) ?: return false
        val facings = runCatchingExceptions {
            manager.cameraIdList.mapNotNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) }.toSet()
        }.getOrDefault(emptySet())
        return CameraCharacteristics.LENS_FACING_FRONT in facings && CameraCharacteristics.LENS_FACING_BACK in facings
    }

    override fun createCallSoundEffectRenderer(volume: Float): CallSoundEffectRenderer = DefaultCallSoundEffectRenderer(context, volume)

    override fun reactionSpread(): Duration = Random.nextLong(0, MAXIMUM_REACTION_SPREAD.inWholeMilliseconds + 1).milliseconds

    override fun reactionCatalogue(): List<MatrixRtcReactionKind> = try {
        coreReactionCatalogue()
    } catch (e: Exception) {
        Timber.e(e, "ElementCall: no reaction catalogue from the core")
        emptyList()
    } catch (e: LinkageError) {
        // No native core: a JVM test over the real stack.
        Timber.e(e, "ElementCall: no reaction catalogue from the core")
        emptyList()
    }
}

/** Reactions one sync delivered together neither rise in a line nor fuse into one sound (037 R2b). */
internal val MAXIMUM_REACTION_SPREAD: Duration = 500.milliseconds
