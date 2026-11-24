/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

class ExportPngWriter(output: OutputStream, private val width: Int, height: Int) : Closeable {
    private val out = DataOutputStream(output)
    private val deflater = Deflater(6)
    private var finished = false
    private val pixels = IntArray(width)
    private val row = ByteArray(width * 4 + 1)
    private val compressed: DeflaterOutputStream

    init {
        out.write(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream()
        DataOutputStream(header).apply { writeInt(width); writeInt(height); write(byteArrayOf(8, 6, 0, 0, 0)) }
        chunk("IHDR", header.toByteArray())
        compressed = DeflaterOutputStream(object : OutputStream() {
            override fun write(value: Int) = chunk("IDAT", byteArrayOf(value.toByte()))
            override fun write(bytes: ByteArray, offset: Int, length: Int) = chunk("IDAT", bytes, offset, length)
        }, deflater, 32768)
    }

    fun append(bitmap: Bitmap, rows: Int = bitmap.height) {
        require(bitmap.width == width && rows in 1..bitmap.height)
        for (y in 0 until rows) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            var index = 1
            pixels.forEach { pixel ->
                row[index++] = (pixel ushr 16).toByte()
                row[index++] = (pixel ushr 8).toByte()
                row[index++] = pixel.toByte()
                row[index++] = (pixel ushr 24).toByte()
            }
            compressed.write(row)
        }
    }

    private fun chunk(type: String, data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val kind = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(kind); update(data, offset, length) }
        out.writeInt(length)
        out.write(kind)
        out.write(data, offset, length)
        out.writeInt(crc.value.toInt())
    }

    override fun close() {
        if (finished) return
        finished = true
        try {
            compressed.finish()
            chunk("IEND", byteArrayOf())
            out.flush()
        } finally {
            deflater.end()
        }
    }
}

object ExportSplitPlanner {
    data class Part(val top: Int, val height: Int)

    fun split(boundaries: List<Int>, total: Int, limit: Int): List<Part> {
        require(total > 0 && limit >= 0)
        if (limit == 0 || total <= limit) return listOf(Part(0, total))
        val result = mutableListOf<Part>()
        var top = 0
        while (top < total) {
            val maximum = minOf(top.toLong() + limit, total.toLong()).toInt()
            val match = boundaries.binarySearch(maximum)
            val index = if (match >= 0) match else -match - 2
            val boundary = boundaries.getOrNull(index)
            val end = boundary?.takeIf { it > top } ?: maximum
            result.add(Part(top, end - top))
            top = end
        }
        return result
    }
}
