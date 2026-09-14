/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

interface ElementCallNavigator {
    fun close()

    /**
     * Ask for the camera permission, if it is not already held.
     *
     * On the navigator because only an Activity can launch a permission request, and the camera is
     * requested in response to a tap rather than up front like the microphone. The answer comes back
     * as [ElementCallScreenEvent.SetCameraPermissionGranted], not as a return value - the user may take as
     * long as they like over it, or never answer at all.
     */
    fun requestCameraPermission()

    /**
     * Raise the system's screen-capture dialog.
     *
     * On the navigator for the same reason as the camera, but for a stronger one: this is not a
     * runtime permission at all and there is no `checkSelfPermission` that could shortcut it. Screen
     * capture is granted as a one-shot token from an Activity result every single time, so sharing
     * again after stopping means coming back through here.
     *
     * The token goes straight to the controller rather than coming back as an event, because it is a
     * platform object with a lifetime rather than a piece of state a screen should hold.
     */
    fun requestScreenCapture()
}
