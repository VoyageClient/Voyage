/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import java.io.File

internal enum class ImageSourceFormat {
    GIF,
    APNG,
    XPM,
    FARBFELD,
    ANIMATED_WEBP,
    STATIC_WEBP_ALPHA,
    OTHER;

    /**
     * Null where the signature does not settle the question, e.g. AVIF or a plain PNG that could
     * still be an APNG we failed to scan. MSC4230 wants the key omitted in that case rather than
     * asserted false.
     */
    fun isAnimated(): Boolean? = when (this) {
        GIF, APNG, ANIMATED_WEBP -> true
        XPM, FARBFELD, STATIC_WEBP_ALPHA -> false
        OTHER -> null
    }
}

internal fun sniffImageFormat(file: File): ImageSourceFormat {
    val head = ByteArray(64)
    val read = try {
        file.inputStream().use { it.read(head) }
    } catch (t: Throwable) {
        return ImageSourceFormat.OTHER
    }
    if (read < 8) return ImageSourceFormat.OTHER
    // GIF: "GIF87a" or "GIF89a"
    if (head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte()) return ImageSourceFormat.GIF
    // PNG signature; differentiate APNG by presence of the acTL chunk in the first ~64 bytes.
    if (read >= 8 &&
            head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte()) {
        // acTL must appear before IDAT — scan the whole file's first ~4 KB to detect.
        return if (containsApngMarker(file)) ImageSourceFormat.APNG else ImageSourceFormat.OTHER
    }
    // RIFF....WEBP — a VP8X header at offset 12 carries ANIM (bit 1) and ALPHA (bit 4) flags;
    // a VP8L chunk is lossless, which may also carry alpha.
    if (read >= 16 &&
            head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
            head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() && head[10] == 'B'.code.toByte() && head[11] == 'P'.code.toByte()) {
        if (head[12] == 'V'.code.toByte() && head[13] == 'P'.code.toByte() && head[14] == '8'.code.toByte()) {
            if (head[15] == 'X'.code.toByte() && read >= 21) {
                val flags = head[20].toInt()
                if (flags and (1 shl 1) != 0) return ImageSourceFormat.ANIMATED_WEBP
                if (flags and (1 shl 4) != 0) return ImageSourceFormat.STATIC_WEBP_ALPHA
            }
            if (head[15] == 'L'.code.toByte()) return ImageSourceFormat.STATIC_WEBP_ALPHA
        }
        return ImageSourceFormat.OTHER
    }
    if (read >= 9 && String(head, 0, 9, Charsets.US_ASCII).startsWith("/* XPM */")) return ImageSourceFormat.XPM
    if (read >= 8 && String(head, 0, 8, Charsets.US_ASCII) == "farbfeld") return ImageSourceFormat.FARBFELD
    return ImageSourceFormat.OTHER
}

private fun containsApngMarker(file: File): Boolean {
    // acTL chunk is required for APNG and must come before the first IDAT.
    val buf = ByteArray(4096)
    return try {
        file.inputStream().use {
            val n = it.read(buf)
            if (n <= 0) return@use false
            val needle = "acTL".toByteArray(Charsets.US_ASCII)
            var i = 0
            while (i <= n - needle.size) {
                var match = true
                for (k in needle.indices) {
                    if (buf[i + k] != needle[k]) { match = false; break }
                }
                if (match) return@use true
                i++
            }
            false
        }
    } catch (t: Throwable) {
        false
    }
}
