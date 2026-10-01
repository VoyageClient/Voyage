/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.telegram

import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeInRange
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TelegramStickerConverterTest {

    // One second at 60fps: a 64px red square fading in.
    private val lottie = """
        {"v":"5.5.2","fr":60,"ip":0,"op":60,"w":64,"h":64,"nm":"t","ddd":0,"assets":[],
         "layers":[{"ddd":0,"ind":1,"ty":1,"nm":"s","sr":1,"ao":0,"sw":64,"sh":64,"sc":"#ff0000",
           "ip":0,"op":60,"st":0,"bm":0,
           "ks":{"o":{"a":1,"k":[{"t":0,"s":[0],"i":{"x":[1],"y":[1]},"o":{"x":[0],"y":[0]}},{"t":60,"s":[100]}]},
                 "r":{"a":0,"k":0},"p":{"a":0,"k":[32,32,0]},"a":{"a":0,"k":[32,32,0]},"s":{"a":0,"k":[100,100,100]}}}]}
    """.trimIndent()

    @Test
    fun `tgs becomes a 30fps animated webp of the same length`() = runBlocking<Unit> {
        val dir = File.createTempFile("tgs-", "").apply { delete(); mkdirs() }
        try {
            val tgs = File(dir, "sticker.tgs").apply { GZIPOutputStream(outputStream()).use { it.write(lottie.toByteArray()) } }
            val result = TelegramStickerConverter().convert(tgs, File(dir, "out.webp"))
            result.animated shouldBeEqualTo true
            result.mimeType shouldBeEqualTo "image/webp"
            result.size shouldBeEqualTo (64 to 64)
            val bytes = result.file.readBytes()
            String(bytes, 0, 4, Charsets.US_ASCII) shouldBeEqualTo "RIFF"
            val durations = anmfDurations(bytes)
            durations.size shouldBeEqualTo 30
            durations.sum() shouldBeInRange 990..1010
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `static webp passes through untouched`() = runBlocking<Unit> {
        val dir = File.createTempFile("webp-", "").apply { delete(); mkdirs() }
        try {
            val webp = File(dir, "sticker.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val result = TelegramStickerConverter().convert(webp, File(dir, "out.webp"))
            result.file shouldBeEqualTo webp
            result.animated shouldBeEqualTo false
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun anmfDurations(bytes: ByteArray): List<Int> {
        val durations = mutableListOf<Int>()
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val type = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = le(bytes, pos + 4, 4)
            if (type == "ANMF") durations += le(bytes, pos + 8 + 12, 3)
            pos += 8 + size + (size and 1)
        }
        return durations
    }

    private fun le(bytes: ByteArray, offset: Int, count: Int): Int =
            (0 until count).fold(0) { acc, i -> acc or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i)) }
}
