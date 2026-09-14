/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.consumer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.lifecycle.lifecycleScope
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.impl.ElementCallPictureInPicture
import io.element.android.call.impl.ElementCallStack
import io.element.android.call.matrix.ElementCallSdkTransport
import io.element.android.call.test.FakeElementCallController
import io.element.android.call.test.FakeElementCallMatrixTransport
import io.element.android.call.ui.ElementCallOverlay

/**
 * The public API, called the way a host calls it: build the stack (over the test transport, so no SDK
 * client is needed), bind picture-in-picture, and draw the overlay over the host's content. Nothing
 * here is asserted; the assertion is that R8 links it all and the app starts.
 */
class ConsumerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The real stack, so every published module is reachable and the consumer rules are exercised.
        val stack = ElementCallStack.Builder(this, FakeElementCallMatrixTransport()).build(lifecycleScope)
        ElementCallPictureInPicture.attach(this, stack.controller)

        // What is drawn: a fake call, so the overlay has something to show without joining anything.
        val controller = FakeElementCallController(
            initialState = ElementCallSnapshot(
                callData = ElementCallData(roomId = RoomId("!consumer:example.org"), isAudioCall = true),
                connection = ElementCallConnection.Connected,
                roomName = "Consumer check",
                isMaximized = false,
            ),
        )
        setContent {
            ElementCallOverlay(controller = controller) { modifier ->
                // The turnkey transport is named so the matrix artifact is linked too.
                Text(text = "Consumer of element-call, with ${ElementCallSdkTransport::class.java.simpleName}", modifier = modifier)
            }
        }
    }
}
