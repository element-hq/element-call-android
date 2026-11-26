/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Our camera, captured before and through a call (feature-hq 045 R49): the lobby's preview and the call's camera are
 * one capture, so the device opens once. One per call attempt, owned by whoever created it, which stops it.
 */
interface MatrixRtcLocalCamera {
    /** Our frames for a self view, whether or not anything publishes them. Collect it once; share it from there. */
    val frames: Flow<MatrixRtcVideoFrame>

    /** Whether the camera is open and capturing. */
    val isCapturing: StateFlow<Boolean>

    /** Whether the camera in use faces the user: what decides whether a self view mirrors (045 R16). */
    val isFrontFacing: StateFlow<Boolean>

    /** Open the camera, front first. A no-op while capturing. Fails when the device has no camera it can open. */
    suspend fun start(): Result<Unit>

    /** Swap between the front and back cameras, keeping the capture running. A no-op while stopped. */
    suspend fun switchCamera(): Result<Unit>

    /** Release the camera. Safe to call when stopped, and twice. */
    fun stop()
}
