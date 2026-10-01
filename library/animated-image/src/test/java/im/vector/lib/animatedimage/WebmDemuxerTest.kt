/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.animatedimage

import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBe
import org.junit.Test

class WebmDemuxerTest {

    private fun fixture(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    @Test
    fun `alpha stream is read from the block additions`() {
        val video = WebmDemuxer.parse(fixture("vp9_alpha.webm"))!!
        video.codecMime shouldBeEqualTo "video/x-vnd.on2.vp9"
        video.width shouldBeEqualTo 16
        video.height shouldBeEqualTo 16
        video.frames.size shouldBeEqualTo 3
        video.hasAlpha shouldBe true
        video.frames.all { it.alpha != null && it.data.isNotEmpty() } shouldBe true
        video.frames.map { it.timeUs } shouldBeEqualTo video.frames.map { it.timeUs }.sorted()
    }

    @Test
    fun `opaque video has no alpha`() {
        val video = WebmDemuxer.parse(fixture("vp9_opaque.webm"))!!
        video.frames.size shouldBeEqualTo 3
        video.hasAlpha shouldBe false
    }

    @Test
    fun `frame durations follow the timestamps`() {
        val video = WebmDemuxer.parse(fixture("vp9_alpha.webm"))!!
        (0 until video.frames.size).map { video.durationUs(it) / 1000 }.all { it in 32L..34L } shouldBe true
    }

    @Test
    fun `truncated file keeps the frames read so far`() {
        val bytes = fixture("vp9_alpha.webm")
        WebmDemuxer.parse(bytes.copyOf(bytes.size - 20)) shouldNotBe null
        WebmDemuxer.parse(ByteArray(0)) shouldBe null
        WebmDemuxer.parse("not a webm".toByteArray()) shouldBe null
    }
}
