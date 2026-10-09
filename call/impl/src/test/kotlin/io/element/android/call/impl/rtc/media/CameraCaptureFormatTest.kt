/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.call.impl.rtc.media

import com.google.common.truth.Truth.assertThat
import io.element.android.call.impl.rtc.media.CameraCaptureFormat.Candidate
import io.element.android.call.impl.rtc.media.CameraCaptureFormat.Size
import org.junit.Test

class CameraCaptureFormatTest {
    @Test
    fun `a 4-3 sensor runs the smallest 4-3 format at 720 or more, scaled to 960x720`() {
        val format = CameraCaptureFormat.choose(PHONE, FOUR_THREE)

        assertThat(format).isEqualTo(CameraCaptureFormat(Candidate(1280, 960, 30), Size(960, 720)))
    }

    @Test
    fun `a 960x720 format is run as is`() {
        val format = CameraCaptureFormat.choose(PHONE + Candidate(960, 720, 30), FOUR_THREE)

        assertThat(format).isEqualTo(CameraCaptureFormat(Candidate(960, 720, 30), Size(960, 720)))
    }

    @Test
    fun `a 16-9 sensor runs 1280x720`() {
        val format = CameraCaptureFormat.choose(PHONE, SIXTEEN_NINE)

        assertThat(format).isEqualTo(CameraCaptureFormat(Candidate(1280, 720, 30), Size(1280, 720)))
    }

    @Test
    fun `the shape comes from the sensor even when the list tops out at 16-9`() {
        val candidates = listOf(Candidate(1920, 1080, 30), Candidate(1280, 720, 30), Candidate(1440, 1080, 30), Candidate(640, 480, 30))

        val format = CameraCaptureFormat.choose(candidates, FOUR_THREE)

        assertThat(format).isEqualTo(CameraCaptureFormat(Candidate(1440, 1080, 30), Size(960, 720)))
    }

    @Test
    fun `a format that cannot reach 30 fps loses to one that can`() {
        val candidates = listOf(Candidate(1280, 960, 15), Candidate(1920, 1440, 30))

        val format = CameraCaptureFormat.choose(candidates, FOUR_THREE)

        assertThat(format?.candidate).isEqualTo(Candidate(1920, 1440, 30))
    }

    @Test
    fun `a camera below 720 runs its largest format unscaled`() {
        val candidates = listOf(Candidate(320, 240, 30), Candidate(640, 480, 30))

        assertThat(CameraCaptureFormat.choose(candidates, FOUR_THREE)).isEqualTo(CameraCaptureFormat(Candidate(640, 480, 30), Size(640, 480)))
    }

    @Test
    fun `with no format of the sensor's shape, the closest shape is used`() {
        val candidates = listOf(Candidate(320, 240, 30), Candidate(640, 360, 30))

        assertThat(CameraCaptureFormat.choose(candidates, 1.7f)?.output).isEqualTo(Size(640, 360))
    }

    @Test
    fun `a camera with nothing of 480 on its long edge runs the largest it has`() {
        val candidates = listOf(Candidate(176, 144, 30), Candidate(320, 240, 30))

        assertThat(CameraCaptureFormat.choose(candidates, FOUR_THREE)?.output).isEqualTo(Size(320, 240))
    }

    @Test
    fun `no format is no choice`() {
        assertThat(CameraCaptureFormat.choose(emptyList(), FOUR_THREE)).isNull()
    }

    @Test
    fun `front and back of different sizes give the same output`() {
        val back = listOf(Candidate(1920, 1440, 30), Candidate(4000, 3000, 30))
        val front = listOf(Candidate(1280, 960, 30))

        assertThat(CameraCaptureFormat.choose(back, FOUR_THREE)?.output).isEqualTo(CameraCaptureFormat.choose(front, FOUR_THREE)?.output)
    }

    @Test
    fun `scaling keeps the shape and rounds to even`() {
        assertThat(CameraCaptureFormat.scaledToShortEdge(1280, 960, 720)).isEqualTo(Size(960, 720))
        assertThat(CameraCaptureFormat.scaledToShortEdge(960, 1280, 720)).isEqualTo(Size(720, 960))
        assertThat(CameraCaptureFormat.scaledToShortEdge(1920, 1080, 720)).isEqualTo(Size(1280, 720))
        assertThat(CameraCaptureFormat.scaledToShortEdge(1080, 810, 720)).isEqualTo(Size(960, 720))
        assertThat(CameraCaptureFormat.scaledToShortEdge(2000, 1000, 333)).isEqualTo(Size(666, 333))
    }

    @Test
    fun `a frame already at or below the short edge is not scaled`() {
        assertThat(CameraCaptureFormat.scaledToShortEdge(960, 720, 720)).isEqualTo(Size(960, 720))
        assertThat(CameraCaptureFormat.scaledToShortEdge(640, 480, 720)).isEqualTo(Size(640, 480))
    }

    private companion object {
        const val FOUR_THREE = 4f / 3f
        const val SIXTEEN_NINE = 16f / 9f

        val PHONE = listOf(
            Candidate(640, 480, 30),
            Candidate(1280, 720, 30),
            Candidate(1280, 960, 30),
            Candidate(1920, 1080, 30),
            Candidate(1920, 1440, 30),
        )
    }
}
