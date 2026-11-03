/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.sample.lab

import android.net.Uri
import io.element.android.call.api.rtc.MatrixRtcMembershipFormat

/**
 * What the lab's QR code carries (feature-hq `harness/lab.md`, "What the platforms owe the lab"), the same on both
 * platforms: `elementcall-lab://join?hs=…&user=…&password=…&room=…&recovery=…&format=…`. Unknown parameters are ignored.
 */
data class SampleLabJoin(
    val homeserver: String,
    val user: String,
    val password: String,
    val roomId: String,
    /** The account's recovery key: a new device joins the account's identity with it, so the others accept its media key. */
    val recoveryKey: String?,
    /** The lab's call format, which its bots use too: a phone in another one would sit in a silent call of its own. */
    val format: MatrixRtcMembershipFormat,
) {
    companion object {
        const val SCHEME = "elementcall-lab"

        fun parse(uri: Uri): SampleLabJoin? {
            if (uri.scheme != SCHEME || uri.host != "join") return null
            return SampleLabJoin(
                homeserver = uri.getQueryParameter("hs") ?: return null,
                user = uri.getQueryParameter("user") ?: return null,
                password = uri.getQueryParameter("password") ?: return null,
                roomId = uri.getQueryParameter("room") ?: return null,
                recoveryKey = uri.getQueryParameter("recovery")?.takeIf { it.isNotBlank() },
                format = labFormat(uri.getQueryParameter("format")),
            )
        }

        /** Where the lab serves its CA: the homeserver's name suffix under `lab.`, on plain HTTP, as a device that trusts nothing yet fetches it. */
        fun certificateUrl(homeserver: String): String? {
            val host = Uri.parse(homeserver).host ?: return null
            val dot = host.indexOf('.').takeIf { it > 0 } ?: return null
            return "http://lab${host.substring(dot)}/lab-ca.crt"
        }
    }
}

/** The lab's name for a format, as the page writes it in the code: `current` or `state`, room state when absent. */
fun labFormat(name: String?): MatrixRtcMembershipFormat =
    if (name == "current") MatrixRtcMembershipFormat.CURRENT else MatrixRtcMembershipFormat.ROOM_STATE

val MatrixRtcMembershipFormat.labName: String
    get() = if (this == MatrixRtcMembershipFormat.CURRENT) "current" else "state"

/** How the Lab tab names the format, the same words as the lab page's banner. */
val MatrixRtcMembershipFormat.label: String
    get() = when (this) {
        MatrixRtcMembershipFormat.CURRENT -> "Current (sticky events)"
        MatrixRtcMembershipFormat.STICKY2025 -> "Sticky 2025"
        MatrixRtcMembershipFormat.ROOM_STATE -> "Room state (legacy)"
    }
