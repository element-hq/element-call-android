/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc

import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.api.matrix.ElementCallStickyEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber
import uniffi.matrix_rtc_ffi.FfiLeaveReason
import uniffi.matrix_rtc_ffi.RawMemberEvent as FfiRawMemberEvent
import uniffi.matrix_rtc_ffi.StickyEvent as FfiStickyEvent

/**
 * Turns the SDK's [ElementCallStickyEvent] into the flat record the RTC core expects.
 *
 * The SDK hands us the whole `m.rtc.member` event as JSON plus its decryption metadata; the FFI
 * wants the MSC4143 content fields pulled apart. Everything except `slot_id` is optional per the
 * MSC - a leave event carries little more than the slot, the member and a reason - so a missing
 * field is normal and must not drop the event.
 */
internal object StickyEventMapper {
    private const val LEAVE_MEMBERSHIP = "leave"

    /** Both spellings of MSC4354's sticky key. The core reads either, and so do we. */
    private val STICKY_KEY_FIELDS = listOf("sticky_key", "msc4354_sticky_key")

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @return the mapped event, or null if it is unusable: unparseable JSON, no `slot_id`, or no
     * sticky key.
     */
    fun map(roomId: RoomId, event: ElementCallStickyEvent): FfiStickyEvent? {
        val content = parseContent(event) ?: return null

        val slotId = content.string("slot_id")
        if (slotId == null) {
            Timber.w("Dropping sticky event ${event.eventId}: no slot_id in content")
            return null
        }
        // The SDK already surfaces the sticky key, so we do not re-read content.msc4354_sticky_key.
        val stickyKey = event.stickyKey
        if (stickyKey == null) {
            Timber.w("Dropping sticky event ${event.eventId}: no sticky key")
            return null
        }

        val member = content.obj("member")
        val application = content.obj("application")
        val membership = member?.string("membership")

        // Workaround for a Rust SDK bug: `sendDelayedEvent` skips the encryption path, so the dead
        // man's switch leave that the core schedules at join time is sent in the clear. In an
        // encrypted room the core discards a membership it cannot vouch for, so the departure never
        // lands and the member stays in the session as a phantom participant until the sticky
        // expires.
        //
        // Deliberately scoped to departures. Vouching for one lets an unauthenticated event remove a
        // member from our view of the session, which is a nuisance; vouching for a join would let it
        // add one, or point key distribution at a device of its choosing. Remove once delayed events
        // go out encrypted.
        val isUnvouchedDeparture = event.encryptionInfo == null && membership == LEAVE_MEMBERSHIP
        if (isUnvouchedDeparture) {
            Timber.w("MatrixRTC: vouching for a cleartext departure of ${member?.string("id")}, delayed events are not encrypted yet")
        }

        return FfiStickyEvent(
            roomId = roomId.value,
            sender = event.sender.value,
            // MSC4143 has no self-asserted device id, so this can only come from decryption metadata.
            // Without it the core cannot target key distribution at a single device. Nothing to give
            // for a cleartext departure: if the core needs a device to match the candidate it is
            // removing, the warning above is what will tell us this workaround was not enough.
            senderDeviceId = event.encryptionInfo?.senderDeviceId?.value,
            // Must otherwise stay distinguishable from "unknown": in an encrypted room a false here
            // drops the member, so we only report a value we actually know.
            wasEncrypted = event.encryptionInfo != null || isUnvouchedDeparture,
            eventType = event.eventType,
            slotId = slotId,
            stickyKey = stickyKey,
            applicationType = application?.string("application_type") ?: application?.string("type"),
            memberId = member?.string("id"),
            membership = membership,
            leaveReason = content.leaveReason(),
            // Passed through verbatim: the core parses it, and degrades a malformed value to
            // "no transports" rather than failing the whole batch.
            transportsJson = content["transports"]?.toString(),
        )
    }

    /**
     * The same event, handed over without being taken apart.
     *
     * Used by every compatibility mode, where [map] cannot be: an Element Call membership states its
     * transports somewhere else, its membership nowhere at all, and its leave as a bare sticky key.
     * Pulling those fields apart here would mean teaching this mapper a second dialect and getting the
     * precedence between them right; the library already contains that translation and applies it to
     * raw content, so the honest thing is to hand the content over and let it parse.
     *
     * @return the mapped event, or null if the content cannot be read at all.
     */
    fun mapRaw(event: ElementCallStickyEvent): FfiRawMemberEvent? {
        val content = parseContent(event) ?: return null

        // Same narrow workaround as [map], for the same Rust SDK bug: the dead man's switch leave is
        // sent in the clear, so in an encrypted room the core would discard the departure and leave a
        // phantom member behind until the sticky expires. Both leave shapes count - a spec `membership:
        // "leave"`, and the legacy bare sticky key, which is a content with no `member` at all.
        // Departures only: vouching for a join would let an unauthenticated event add a member, or
        // point key distribution at a device of its choosing.
        val member = content.obj("member")
        val isSpecLeave = member?.string("membership") == LEAVE_MEMBERSHIP
        val isLegacyLeave = member == null && STICKY_KEY_FIELDS.any { content[it] != null }
        val isUnvouchedDeparture = event.encryptionInfo == null && (isSpecLeave || isLegacyLeave)
        if (isUnvouchedDeparture) {
            Timber.w("MatrixRTC: vouching for a cleartext departure of ${member?.string("id")}, delayed events are not encrypted yet")
        }

        return FfiRawMemberEvent(
            sender = event.sender.value,
            // Only decryption can attest a device. Element Call runs as a widget and so has no
            // decryption metadata to offer either; the library falls back to the device the content
            // claims, ranked below an attested one, which is the only way a media key can travel in
            // either direction with such a peer.
            senderDeviceId = event.encryptionInfo?.senderDeviceId?.value,
            wasEncrypted = event.encryptionInfo != null || isUnvouchedDeparture,
            eventType = event.eventType,
            contentJson = content.toString(),
        )
    }

    /**
     * The `slot_id` an event claims, for logging only.
     *
     * A membership in a different slot is a membership in a different call, and the core keeps a
     * separate session per slot - so a peer whose slot id disagrees with ours is absent from our
     * roster while its media plays normally, because the transport is keyed by room rather than by
     * slot. That reads exactly like a membership we never received, which is why the value is worth
     * a log line even though nothing reads it.
     */
    fun slotIdOf(event: ElementCallStickyEvent): String? = parseContent(event)?.string("slot_id")

    private fun parseContent(event: ElementCallStickyEvent): JsonObject? {
        return try {
            json.parseToJsonElement(event.eventJson).let { it as? JsonObject }?.obj("content")
        } catch (throwable: Throwable) {
            // Never log eventJson itself, it is user-adjacent content.
            Timber.w(throwable, "Dropping sticky event ${event.eventId}: cannot parse content")
            null
        }
    }

    /** Accepts both the object form `{"code": .., "reason": ..}` and a bare reason code string. */
    private fun JsonObject.leaveReason(): FfiLeaveReason? {
        obj("leave_reason")?.let { reason ->
            val code = reason.string("code") ?: return null
            return FfiLeaveReason(code = code, reason = reason.string("reason"))
        }
        return string("leave_reason")?.let { FfiLeaveReason(code = it, reason = null) }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
}
