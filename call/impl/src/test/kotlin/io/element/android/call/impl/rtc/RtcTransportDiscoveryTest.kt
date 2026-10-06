/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import com.google.common.truth.Truth.assertThat
import io.element.android.call.test.FakeElementCallMatrixTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

private const val A_SERVICE_URL = "https://sfu.example.org/jwt"

class RtcTransportDiscoveryTest {
    private val sut = RtcTransportDiscovery(FakeElementCallMatrixTransport())

    @Test
    fun `parses the MSC4143 discovery response`() {
        // The response field is rtc_transports, not transports.
        val transports = sut.parseTransports(
            """{ "rtc_transports": [ { "type": "livekit", "livekit_service_url": "$A_SERVICE_URL" } ] }""",
            RtcTransportDiscovery.TRANSPORTS_KEY,
        )

        assertThat(transports).containsExactly(liveKit(A_SERVICE_URL))
    }

    @Test
    fun `parses the deprecated well-known foci list`() {
        val transports = sut.parseTransports(
            """
            {
              "m.homeserver": { "base_url": "https://matrix-client.example.org" },
              "org.matrix.msc4143.rtc_foci": [ { "type": "livekit", "livekit_service_url": "$A_SERVICE_URL" } ]
            }
            """.trimIndent(),
            RtcTransportDiscovery.RTC_FOCI_KEY,
        )

        assertThat(transports).containsExactly(liveKit(A_SERVICE_URL))
    }

    @Test
    fun `parses the stable well-known foci alias`() {
        val transports = sut.parseTransports(
            """{ "m.rtc_foci": [ { "type": "livekit", "livekit_service_url": "$A_SERVICE_URL" } ] }""",
            RtcTransportDiscovery.RTC_FOCI_KEY_ALIAS,
        )

        assertThat(transports).containsExactly(liveKit(A_SERVICE_URL))
    }

    @Test
    fun `keeps ordering, the list is by priority`() {
        val transports = sut.parseTransports(
            """
            {
              "rtc_transports": [
                { "type": "livekit", "livekit_service_url": "https://first.example.org/jwt" },
                { "type": "livekit", "livekit_service_url": "https://second.example.org/jwt" }
              ]
            }
            """.trimIndent(),
            RtcTransportDiscovery.TRANSPORTS_KEY,
        )

        assertThat(transports).containsExactly(
            liveKit("https://first.example.org/jwt"),
            liveKit("https://second.example.org/jwt"),
        ).inOrder()
    }

    @Test
    fun `an entry is passed through verbatim for the core to judge`() {
        val transports = sut.parseTransports(
            """{ "rtc_transports": [ { "type": "somethingelse", "url": "https://example.org" } ] }""",
            RtcTransportDiscovery.TRANSPORTS_KEY,
        )

        assertThat(transports).containsExactly(json("""{ "type": "somethingelse", "url": "https://example.org" }"""))
    }

    @Test
    fun `an entry with no type is dropped`() {
        val transports = sut.parseTransports(
            """{ "rtc_transports": [ { "livekit_service_url": "$A_SERVICE_URL" } ] }""",
            RtcTransportDiscovery.TRANSPORTS_KEY,
        )

        assertThat(transports).isEmpty()
    }

    @Test
    fun `a well-known with no foci yields nothing so the caller can report it`() {
        val transports = sut.parseTransports(
            """{ "m.homeserver": { "base_url": "https://matrix-client.example.org" } }""",
            RtcTransportDiscovery.RTC_FOCI_KEY,
        )

        assertThat(transports).isEmpty()
    }

    @Test
    fun `unparseable json yields nothing rather than throwing`() {
        assertThat(sut.parseTransports("not json", RtcTransportDiscovery.TRANSPORTS_KEY)).isEmpty()
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun liveKit(serviceUrl: String) = json("""{ "type": "livekit", "livekit_service_url": "$serviceUrl" }""")
}
