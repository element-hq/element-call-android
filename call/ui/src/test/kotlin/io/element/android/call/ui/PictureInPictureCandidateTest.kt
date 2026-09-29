/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.ui

import com.google.common.truth.Truth.assertThat
import io.element.android.call.api.rtc.MatrixRtcTileId
import io.element.android.call.api.rtc.MatrixRtcTileKind
import io.element.android.call.test.aCameraParticipant
import io.element.android.call.test.aTile
import io.element.android.call.test.aWindowedRoster
import org.junit.Test

private const val ANOTHER_REMOTE_MEMBER_ID = "3d7f0c7c6f2a4d0b9e8a1c5b7d6e4f21"

/** What the one-tile windows show (spec 003 R68): the spotlight, then video, then anyone, then us. */
class PictureInPictureCandidateTest {
    @Test
    fun `the spotlight wins, whatever ranks first`() {
        val snapshot = aCallSnapshot(participants = listOf(aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false)))
            .copy(roster = listOf(aTile(A_REMOTE_MEMBER_ID, hasVideo = true), aTile(ANOTHER_REMOTE_MEMBER_ID, hasVideo = true)).previewRoster())

        val chosen = snapshot.pictureInPictureCandidate(MatrixRtcTileId(ANOTHER_REMOTE_MEMBER_ID, MatrixRtcTileKind.PERSON))

        assertThat(chosen?.id?.memberId).isEqualTo(ANOTHER_REMOTE_MEMBER_ID)
        assertThat(chosen?.hasVideo).isTrue()
        assertThat(chosen?.isLocal).isFalse()
    }

    @Test
    fun `with no spotlight the first tile in order with video, else the first remote tile`() {
        val withVideo = aCallSnapshot().copy(
            roster = listOf(aTile(A_REMOTE_MEMBER_ID, hasVideo = false), aTile(ANOTHER_REMOTE_MEMBER_ID, hasVideo = true)).previewRoster(),
        )
        assertThat(withVideo.pictureInPictureCandidate(null)?.id?.memberId).isEqualTo(ANOTHER_REMOTE_MEMBER_ID)

        val noVideo = aCallSnapshot().copy(
            roster = listOf(aTile(A_REMOTE_MEMBER_ID, hasVideo = false), aTile(ANOTHER_REMOTE_MEMBER_ID, hasVideo = false)).previewRoster(),
        )
        assertThat(noVideo.pictureInPictureCandidate(null)?.id?.memberId).isEqualTo(A_REMOTE_MEMBER_ID)
        assertThat(noVideo.pictureInPictureCandidate(null)?.hasVideo).isFalse()
    }

    @Test
    fun `a reference outside the window is a candidate without video`() {
        val snapshot = aCallSnapshot().copy(
            roster = aWindowedRoster(aTile(A_REMOTE_MEMBER_ID, hasVideo = true), detailFor = emptySet()),
        )

        val chosen = snapshot.pictureInPictureCandidate(null)

        assertThat(chosen?.id?.memberId).isEqualTo(A_REMOTE_MEMBER_ID)
        assertThat(chosen?.hasVideo).isFalse()
    }

    @Test
    fun `alone, ourselves, with our camera`() {
        val snapshot = aCallSnapshot(participants = listOf(aCameraParticipant(A_LOCAL_MEMBER_ID, isLocal = true, isCameraMuted = false)))
            .copy(isCameraEnabled = true)

        val chosen = snapshot.pictureInPictureCandidate(null)

        assertThat(chosen?.isLocal).isTrue()
        assertThat(chosen?.id?.memberId).isEqualTo(A_LOCAL_MEMBER_ID)
        assertThat(chosen?.hasVideo).isTrue()
        assertThat(aCallSnapshot().pictureInPictureCandidate(null)).isNull()
    }
}
