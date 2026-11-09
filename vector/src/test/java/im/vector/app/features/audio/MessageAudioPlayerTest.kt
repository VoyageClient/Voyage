/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.audio

import org.amshove.kluent.shouldBe
import org.junit.Test

class MessageAudioPlayerTest {

    private fun sniff(head: String, count: Int = head.length) =
            MessageAudioPlayer.isMidiHeader(head.toByteArray(Charsets.ISO_8859_1).copyOf(12), count)

    @Test
    fun `standard MIDI file is MIDI`() {
        sniff("MThd\u0000\u0000\u0000\u0006\u0000\u0001") shouldBe true
    }

    @Test
    fun `mobile XMF is MIDI`() {
        sniff("XMF_2.00") shouldBe true
    }

    @Test
    fun `RIFF MIDI is MIDI but RIFF WAVE is not`() {
        sniff("RIFF\u0010\u0000\u0000\u0000RMID") shouldBe true
        sniff("RIFF\u0010\u0000\u0000\u0000WAVE") shouldBe false
    }

    @Test
    fun `other audio is not MIDI`() {
        sniff("OggS\u0000\u0002") shouldBe false
        sniff("ID3\u0004") shouldBe false
    }

    @Test
    fun `truncated header is not MIDI`() {
        sniff("MTh") shouldBe false
        sniff("RIFF\u0010\u0000\u0000\u0000RMID", count = 10) shouldBe false
    }
}
