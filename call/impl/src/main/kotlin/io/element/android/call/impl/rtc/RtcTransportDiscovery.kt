/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import io.element.android.call.api.matrix.ElementCallMatrixTransport
import io.element.android.call.impl.util.runCatchingExceptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber

/**
 * Finds out which RTC transports the homeserver offers.
 *
 * Two sources, in order:
 * 1. the MSC4143 discovery endpoint, which ruma still lists as unstable-only - no `/v1/` path
 *    exists yet despite the module being named after one,
 * 2. the deprecated `rtc_foci` list in the well-known, for servers that answer 404 on the endpoint.
 *    matrix.org is one of them, so the fallback is not hypothetical.
 *
 * The Rust SDK already does exactly this in `Client::rtc_transports()`, with caching and the same
 * fallback, but it is not exposed over the FFI yet. Replace this class with a call through once it
 * is - that also gets the deprecated path retired on the SDK's schedule rather than ours.
 */
internal class RtcTransportDiscovery(
    private val transport: ElementCallMatrixTransport,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun discover(): Result<List<JsonObject>> = runCatchingExceptions {
        fromEndpoint() ?: fromWellKnown()
    }

    /** The transports as the JSON array the core's backend answers with, entries verbatim; `[]` for none. */
    suspend fun discoverJson(): Result<String> = discover().map { JsonArray(it).toString() }

    /**
     * @return the advertised transports, or null if the endpoint is unavailable, in which case the
     * caller should fall back.
     */
    private suspend fun fromEndpoint(): List<JsonObject>? {
        val url = "${transport.homeserverUrl.trimEnd('/')}$TRANSPORTS_PATH"
        return transport.getUrl(url).fold(
            onSuccess = { body ->
                parseTransports(body, TRANSPORTS_KEY).also {
                    Timber.i("MatrixRTC: discovery endpoint advertises $it")
                }
            },
            onFailure = { throwable ->
                // Expected on servers without MSC4143 support: they answer 404.
                Timber.i("MatrixRTC: discovery endpoint unavailable (${throwable.message}), falling back to well-known")
                null
            },
        )
    }

    private suspend fun fromWellKnown(): List<JsonObject> {
        // Well-known is served from the server name, which is usually not the homeserver URL.
        val url = "https://${transport.userIdServerName()}$WELL_KNOWN_PATH"
        return transport.getUrl(url).fold(
            onSuccess = { body ->
                val transports = parseTransports(body, RTC_FOCI_KEY)
                    .ifEmpty { parseTransports(body, RTC_FOCI_KEY_ALIAS) }
                Timber.i("MatrixRTC: well-known advertises $transports")
                transports
            },
            onFailure = { throwable ->
                Timber.w(throwable, "MatrixRTC: cannot read well-known from $url")
                emptyList()
            },
        )
    }

    internal fun parseTransports(body: String, key: String): List<JsonObject> {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (throwable: Throwable) {
            Timber.w(throwable, "MatrixRTC: cannot parse transport response")
            null
        } ?: return emptyList()

        val transports = root[key] as? JsonArray ?: return emptyList()
        // Verbatim: the core reads the transport, so only an entry with no type at all is dropped.
        return transports.mapNotNull { element -> (element as? JsonObject)?.takeIf { it.string("type") != null } }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        private const val TRANSPORTS_PATH = "/_matrix/client/unstable/org.matrix.msc4143/rtc/transports"
        private const val WELL_KNOWN_PATH = "/.well-known/matrix/client"

        /** The MSC4143 discovery endpoint's response field. */
        internal const val TRANSPORTS_KEY = "rtc_transports"

        /** The deprecated well-known list, and its stable alias. */
        internal const val RTC_FOCI_KEY = "org.matrix.msc4143.rtc_foci"
        internal const val RTC_FOCI_KEY_ALIAS = "m.rtc_foci"
    }
}
