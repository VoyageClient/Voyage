/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import org.amshove.kluent.shouldBeEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Mp4MetadataScrubberTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a value in a QuickTime ilst is zeroed without disturbing any box header`() {
        // The moov-level meta an Android muxer writes: com.android.version is identifying, so its
        // value must go — but the entry's type is a key index and the box sits before the tracks, so
        // rewriting any header here makes the whole file unreadable to Android. See the box structure
        // below for why only the value bytes may change.
        val value = "SECRET".toByteArray(Charsets.ISO_8859_1)
        val dataBox = box("data", be32(1) + be32(0) + value)
        val ilstEntry = be32(8 + dataBox.size) + be32(1) + dataBox
        val hdlr = box("hdlr", be32(0) + be32(0) + "mdta".toByteArray() + ByteArray(12))
        val keys = box("keys", be32(0) + be32(1) + box("mdta", "com.android.version".toByteArray()))
        val meta = box("meta", hdlr + keys + box("ilst", ilstEntry))
        val original = ftyp() + box("moov", meta)

        val scrubbed = scrub(original)

        Mp4MetadataScrubber.scrub(fileOf(original)) shouldBeEqualTo Mp4MetadataScrubber.Outcome.SCRUBBED
        scrubbed.size shouldBeEqualTo original.size

        val dataAt = scrubbed.indexOf("data")
        // The key index the entry is named by, and the 'data' box's size and type, must be untouched:
        // rewriting any of them is what left these files unreadable. Only the value may change.
        scrubbed.copyOfRange(dataAt - 8, dataAt - 4).toList() shouldBeEqualTo be32(1).toList()
        scrubbed.copyOfRange(dataAt - 4, dataAt).toList() shouldBeEqualTo be32(8 + 4 + 4 + value.size).toList()
        val valueStart = dataAt + 4 + 8
        scrubbed.copyOfRange(valueStart, valueStart + value.size).toList() shouldBeEqualTo ByteArray(value.size).toList()
        // The key name stays; only its value was blanked.
        (scrubbed.indexOf("com.android.version") >= 0) shouldBeEqualTo true
    }

    @Test
    fun `an identifying box in udta is overwritten as free`() {
        val geo = box("©xyz", "GEO!".toByteArray(Charsets.ISO_8859_1))
        val original = ftyp() + box("moov", box("udta", geo))

        Mp4MetadataScrubber.scrub(fileOf(original)) shouldBeEqualTo Mp4MetadataScrubber.Outcome.SCRUBBED
        val scrubbed = original.let(::scrub)

        val payloadAt = scrubbed.indexOf("GEO!")
        scrubbed.copyOfRange(payloadAt - 4, payloadAt).toList() shouldBeEqualTo "free".toByteArray(Charsets.ISO_8859_1).toList()
    }

    @Test
    fun `a file with nothing identifying is left alone`() {
        val original = ftyp() + box("moov", box("mvhd", ByteArray(100)))

        Mp4MetadataScrubber.scrub(fileOf(original)) shouldBeEqualTo Mp4MetadataScrubber.Outcome.NOTHING_TO_STRIP
        scrub(original).toList() shouldBeEqualTo original.toList()
    }

    @Test
    fun `something that is not an mp4 is reported unsupported`() {
        val original = "this is plainly not an mp4 container".toByteArray(Charsets.ISO_8859_1)

        Mp4MetadataScrubber.scrub(fileOf(original)) shouldBeEqualTo Mp4MetadataScrubber.Outcome.UNSUPPORTED
    }

    private var counter = 0

    private fun fileOf(bytes: ByteArray): File =
            tmp.newFile("in${counter++}.mp4").apply { writeBytes(bytes) }

    /** Scrub a copy and hand back the resulting bytes. */
    private fun scrub(bytes: ByteArray): ByteArray = fileOf(bytes).also { Mp4MetadataScrubber.scrub(it) }.readBytes()

    private fun ftyp() = box("ftyp", "mp42".toByteArray() + be32(0) + "mp42".toByteArray())

    private fun box(type: String, payload: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        require(typeBytes.size == 4)
        return be32(8 + payload.size) + typeBytes + payload
    }

    private fun be32(value: Int) = byteArrayOf(
            (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun ByteArray.indexOf(text: String): Int {
        val needle = text.toByteArray(Charsets.ISO_8859_1)
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
            return start
        }
        return -1
    }
}
