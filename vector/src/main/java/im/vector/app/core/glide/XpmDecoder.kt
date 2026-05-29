/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.glide

import android.graphics.Bitmap
import android.graphics.Color
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapResource
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Decoder for the XPM (X PixMap, version 3) text-based image format. Glide has no built-in
 * support and the format isn't decoded by any common Android library; this implementation is
 * minimal but covers the cases that turn up in practice (XPM3, hex / "None" / named colors,
 * cpp >= 1).
 */
internal class XpmDecoder(private val bitmapPool: BitmapPool) : ResourceDecoder<InputStream, Bitmap> {

    override fun handles(source: InputStream, options: Options): Boolean {
        if (!source.markSupported()) return false
        source.mark(MAGIC.size)
        return try {
            val buf = ByteArray(MAGIC.size)
            val read = source.read(buf)
            read == MAGIC.size && buf.contentEquals(MAGIC)
        } finally {
            source.reset()
        }
    }

    override fun decode(source: InputStream, width: Int, height: Int, options: Options): Resource<Bitmap>? {
        val strings = extractQuotedStrings(source) ?: return null
        if (strings.isEmpty()) return null

        val header = strings[0].split(WHITESPACE).filter { it.isNotEmpty() }
        if (header.size < 4) return null
        val w = header[0].toIntOrNull() ?: return null
        val h = header[1].toIntOrNull() ?: return null
        val ncolors = header[2].toIntOrNull() ?: return null
        val cpp = header[3].toIntOrNull() ?: return null
        if (w <= 0 || h <= 0 || ncolors < 0 || cpp <= 0) return null
        if (strings.size < 1 + ncolors + h) return null

        val palette = HashMap<String, Int>(ncolors)
        for (i in 0 until ncolors) {
            val entry = strings[1 + i]
            if (entry.length < cpp) continue
            val key = entry.substring(0, cpp)
            palette[key] = parseColorEntry(entry.substring(cpp))
        }

        val bitmap = bitmapPool.get(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            val line = strings[1 + ncolors + y]
            for (x in 0 until w) {
                val start = x * cpp
                val end = start + cpp
                row[x] = if (end <= line.length) palette[line.substring(start, end)] ?: Color.BLACK else Color.BLACK
            }
            bitmap.setPixels(row, 0, w, 0, y, w, 1)
        }
        return BitmapResource.obtain(bitmap, bitmapPool)
    }

    private fun extractQuotedStrings(source: InputStream): List<String>? {
        // Concatenate everything between double quotes; comments and surrounding C syntax
        // are skipped naturally because they live outside the quotes.
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inString = false
        var escaped = false
        BufferedReader(InputStreamReader(source, Charsets.ISO_8859_1)).use { reader ->
            while (true) {
                val ch = reader.read()
                if (ch == -1) break
                val c = ch.toChar()
                when {
                    escaped -> { sb.append(c); escaped = false }
                    inString && c == '\\' -> escaped = true
                    inString && c == '"' -> { out.add(sb.toString()); sb.clear(); inString = false }
                    inString -> sb.append(c)
                    c == '"' -> inString = true
                    else -> Unit
                }
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun parseColorEntry(rest: String): Int {
        // Entry shape: "<key> <color> [<key> <color> ...]" — keys are c, m, g, g4, s.
        // Prefer color (c), fall back to grayscale variants, finally mono.
        val tokens = rest.split(WHITESPACE).filter { it.isNotEmpty() }
        val byKey = HashMap<String, MutableList<String>>()
        var i = 0
        while (i < tokens.size) {
            val key = tokens[i]
            i++
            val value = StringBuilder()
            while (i < tokens.size && tokens[i] !in COLOR_KEYS) {
                if (value.isNotEmpty()) value.append(' ')
                value.append(tokens[i])
                i++
            }
            byKey.getOrPut(key) { mutableListOf() }.add(value.toString())
        }
        val raw = byKey["c"]?.firstOrNull()
                ?: byKey["g"]?.firstOrNull()
                ?: byKey["g4"]?.firstOrNull()
                ?: byKey["m"]?.firstOrNull()
                ?: return Color.BLACK
        return parseColor(raw)
    }

    private fun parseColor(value: String): Int {
        val v = value.trim()
        if (v.equals("none", ignoreCase = true)) return Color.TRANSPARENT
        if (v.startsWith("#")) return parseHexColor(v.substring(1)) ?: Color.BLACK
        NAMED_COLORS[v.lowercase()]?.let { return it }
        return Color.BLACK
    }

    private fun parseHexColor(hex: String): Int? {
        return when (hex.length) {
            3 -> {
                // #RGB → expand each nibble.
                val r = hex[0].digitToIntOrNull(16) ?: return null
                val g = hex[1].digitToIntOrNull(16) ?: return null
                val b = hex[2].digitToIntOrNull(16) ?: return null
                Color.rgb(r * 17, g * 17, b * 17)
            }
            6 -> Color.rgb(
                    hex.substring(0, 2).toInt(16),
                    hex.substring(2, 4).toInt(16),
                    hex.substring(4, 6).toInt(16),
            )
            12 -> Color.rgb(
                    // X11 12-hex-digit form uses 16 bits per channel; collapse to 8.
                    hex.substring(0, 4).toInt(16) ushr 8,
                    hex.substring(4, 8).toInt(16) ushr 8,
                    hex.substring(8, 12).toInt(16) ushr 8,
            )
            else -> null
        }
    }

    companion object {
        private val MAGIC = "/* XPM */".toByteArray(Charsets.US_ASCII)
        private val WHITESPACE = Regex("\\s+")
        private val COLOR_KEYS = setOf("c", "m", "g", "g4", "s")
        // Minimal subset of X11 named colors — covers what XPMs in the wild usually reference.
        private val NAMED_COLORS = mapOf(
                "black" to Color.BLACK,
                "white" to Color.WHITE,
                "red" to Color.RED,
                "green" to Color.GREEN,
                "blue" to Color.BLUE,
                "yellow" to Color.YELLOW,
                "cyan" to Color.CYAN,
                "magenta" to Color.MAGENTA,
                "gray" to Color.GRAY,
                "grey" to Color.GRAY,
                "darkgray" to Color.DKGRAY,
                "darkgrey" to Color.DKGRAY,
                "lightgray" to Color.LTGRAY,
                "lightgrey" to Color.LTGRAY,
        )
    }
}
