/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample

import app.cash.turbine.test
import io.element.android.call.api.ElementCallConnection
import io.element.android.call.api.ElementCallData
import io.element.android.call.api.ElementCallSnapshot
import io.element.android.call.api.rtc.MatrixRtcReactionKind
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.test.aTile
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The fixtures' echo of our own reactions, which stands in for the core's. */
class SampleElementCallControllerTest {
    @Test
    fun `our reaction comes back, unless reactions are off`() = runTest {
        val controller = SampleElementCallController(backgroundScope)
        controller.start(
            ElementCallSnapshot(
                callData = ElementCallData(roomId = RoomId("!room:example.org"), isAudioCall = false, isStartingCall = false),
                connection = ElementCallConnection.Connected,
                ownTile = aTile("ourMemberId"),
            ),
        )
        val party = MatrixRtcReactionKind(name = "party", emoji = "🎉")

        controller.reactions.test {
            controller.sendReaction(party)
            awaitItem()

            controller.setShowsReactions(false)
            controller.sendReaction(party)
            expectNoEvents()
        }
    }
}
