/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.test

import io.element.android.call.api.rtc.id.DeviceId
import io.element.android.call.api.rtc.id.EventId
import io.element.android.call.api.rtc.id.RoomId
import io.element.android.call.api.rtc.id.UserId

/** Our own identity in tests. */
val A_USER_ID = UserId("@alice:server.org")
val A_DEVICE_ID = DeviceId("ABCDEFGH")

/** Somebody else. */
val A_USER_ID_2 = UserId("@bob:server.org")
val A_DEVICE_ID_2 = DeviceId("BOBDEVICE")

val A_ROOM_ID = RoomId("!aRoomId:domain")
val A_ROOM_ID_2 = RoomId("!aRoomId2:domain")

val AN_EVENT_ID = EventId("\$anEventId")

const val A_HOMESERVER_URL = "https://matrix-client.server.org"
const val A_SERVER_NAME = "server.org"
