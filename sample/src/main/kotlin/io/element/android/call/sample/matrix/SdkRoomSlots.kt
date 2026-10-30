/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample.matrix

import io.element.android.call.api.ElementCallDispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import org.matrix.rtc.FfiEventEncryption
import org.matrix.rtc.FfiEventIn
import org.matrix.rustcomponents.sdk.Client
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The `m.rtc.slot` spellings, which the core asks for as state. */
internal val SLOT_EVENT_TYPES = setOf("m.rtc.slot", "org.matrix.msc4143.rtc.slot")

private const val RETRY_AFTER_MS = 5_000L
private val json = Json { ignoreUnknownKeys = true }

/**
 * The room's slot state as complete sets per type, read from the homeserver rather than the SDK's store: the store only
 * holds sliding sync's `required_state`, which has no slot type, so it reads every room as slotless and the sticky formats
 * could never join. As the core's own SDK backend does (`matrix-rtc-matrix-sdk` `state_snapshot`). Read again whenever
 * [wakes] emits after its first value; a failed read is retried and never delivered as "no slots".
 */
internal fun Client.slotEventSets(
    roomId: String,
    eventTypes: List<String>,
    wakes: Flow<*>,
    dispatchers: ElementCallDispatchers,
): Flow<Map<String, List<FfiEventIn>>> = channelFlow {
    suspend fun readUntilRead() {
        while (true) {
            runCatchingExceptions { readState(roomId, eventTypes) }
                .onSuccess { return send(it) }
                .onFailure { Timber.w(it, "MatrixRTC backend: could not read the slot state of $roomId") }
            delay(RETRY_AFTER_MS)
        }
    }
    readUntilRead()
    wakes.drop(1).conflate().collect { readUntilRead() }
}.flowOn(dispatchers.io)

private fun Client.readState(roomId: String, eventTypes: List<String>): Map<String, List<FfiEventIn>> {
    val session = session()
    val url = URL("${session.homeserverUrl.trimEnd('/')}/_matrix/client/v3/rooms/${URLEncoder.encode(roomId, "UTF-8")}/state")
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")
        check(connection.responseCode == HttpURLConnection.HTTP_OK) { "GET /state answered ${connection.responseCode}" }
        val events = json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }) as JsonArray
        val read = events.mapNotNull { (it as? JsonObject)?.toFfiEventIn() }.filter { it.eventType in eventTypes }
        return eventTypes.associateWith { type -> read.filter { it.eventType == type } }
    } finally {
        connection.disconnect()
    }
}

private fun JsonObject.toFfiEventIn(): FfiEventIn? {
    fun string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    return FfiEventIn(
        eventId = string("event_id") ?: return null,
        sender = string("sender") ?: return null,
        eventType = string("type") ?: return null,
        stateKey = string("state_key"),
        originServerTs = (this["origin_server_ts"] as? JsonPrimitive)?.longOrNull?.toULong() ?: return null,
        contentJson = json.encodeToString(JsonObject.serializer(), this["content"] as? JsonObject ?: return null),
        encryption = FfiEventEncryption(encrypted = false, senderDeviceId = null, senderConfirmed = null),
    )
}
