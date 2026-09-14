/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.matrix.temporary.widget

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.id.UserId
import io.element.android.call.api.matrix.ElementCallToDeviceMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ToDeviceRelayTest {
    @Test
    fun `a subscriber only hears the types it asked for`() = runTest {
        val relay = ToDeviceRelay()

        relay.subscribe(setOf("a.type")).test {
            relay.publish(aMessage("another.type"))
            relay.publish(aMessage("a.type"))

            assertThat(awaitItem().eventType).isEqualTo("a.type")
            expectNoEvents()
        }
    }

    @Test
    fun `every subscriber of a type hears each message once`() = runTest {
        val relay = ToDeviceRelay()

        relay.subscribe(setOf("a.type")).test {
            val first = this
            relay.subscribe(setOf("a.type", "another.type")).test {
                relay.publish(aMessage("a.type"))

                assertThat(first.awaitItem().eventType).isEqualTo("a.type")
                assertThat(awaitItem().eventType).isEqualTo("a.type")
            }
        }
    }

    private fun aMessage(eventType: String) = ElementCallToDeviceMessage(
        eventType = eventType,
        senderId = UserId("@bob:example.org"),
        content = "{}",
        encryptionInfo = null,
    )
}
