/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.api.rtc

import android.content.Intent

/**
 * The user's permission to capture the screen, as the platform hands it back.
 *
 * A wrapper around the result `Intent` of
 * `MediaProjectionManager.createScreenCaptureIntent()` rather than the `Intent` itself, so that
 * [MatrixRtcCall.setScreenShareEnabled] says what it wants in its own terms and a fake can satisfy
 * it without building a platform object.
 *
 * **Single use.** The platform spends it when the projection is claimed, so a token cannot be kept
 * and replayed to start sharing a second time - stopping and sharing again means going back to the
 * system dialog. Holding one is holding a grant the user made once, so it should not outlive the
 * share it was asked for.
 */
data class MatrixRtcScreenCaptureToken(val resultData: Intent)
