/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.animatedimage

import timber.log.Timber
import java.io.File
import java.io.InputStream

/**
 * Which of the animated image formats a file is, if any.
 *
 * Read from the bytes rather than the mime type or the extension: `image/webp` and `image/png` are
 * each shared between a still and an animated form, and the difference decides whether the file can
 * be edited frame by frame or is flattened to one picture.
 */
enum class AnimatedImageFormat {
    GIF,
    APNG,
    WEBP;

    companion object {

        fun detect(file: File): AnimatedImageFormat? = try {
            file.inputStream().buffered().use { detect(it) }
        } catch (t: Throwable) {
            Timber.w(t, "Animated: cannot read $file")
            null
        }

        fun detect(input: InputStream): AnimatedImageFormat? {
            val stream = if (input.markSupported()) input else input.buffered()
            stream.mark(SIGNATURE_BYTES)
            val signature = ByteArray(SIGNATURE_BYTES)
            if (!stream.readFullyOrNull(signature)) return null
            return when {
                signature.startsWith(GIF_SIGNATURE) -> GIF
                signature.startsWith(PNG_SIGNATURE) -> {
                    // The signature is eight bytes, not twelve; rewind so the chunk walk starts on
                    // a chunk boundary rather than four bytes into IHDR.
                    stream.reset()
                    if (!stream.skipFully(PNG_SIGNATURE.size.toLong())) return null
                    APNG.takeIf { stream.hasApngControlChunk() }
                }
                signature.startsWith(RIFF_SIGNATURE) && signature.regionMatches(WEBP_SIGNATURE, at = 8) ->
                    // A RIFF header is exactly the twelve bytes just read, so the chunks start here.
                    WEBP.takeIf { stream.hasWebpAnimationChunk() }
                else -> null
            }
        }

        /**
         * An `acTL` chunk is what makes a PNG an APNG, and the spec puts it before the first `IDAT`.
         * The chunk lengths are followed rather than the bytes searched, so a still image that
         * happens to contain those four characters is not mistaken for an animation.
         */
        private fun InputStream.hasApngControlChunk(): Boolean =
                walkChunks(stopAt = "IDAT", lengthComesFirst = true) { it == "acTL" }

        /**
         * `ANIM` holds the loop count and only appears in an animation. It follows `VP8X` near the
         * top of the file, so a handful of chunks settles it — walking on would mean reading a whole
         * still image to conclude it is still.
         */
        private fun InputStream.hasWebpAnimationChunk(): Boolean =
                walkChunks(stopAt = null, lengthComesFirst = false, maxChunks = WEBP_HEADER_CHUNKS) { it == "ANIM" }

        /**
         * @param lengthComesFirst PNG writes the length then the type; RIFF writes the type then a
         * little-endian length, and pads odd-sized chunks to an even boundary.
         */
        private fun InputStream.walkChunks(
                stopAt: String?,
                lengthComesFirst: Boolean,
                maxChunks: Int = Int.MAX_VALUE,
                matches: (String) -> Boolean,
        ): Boolean {
            var scanned = 0
            var chunks = 0
            while (scanned < MAX_SCAN_BYTES && chunks++ < maxChunks) {
                val header = ByteArray(CHUNK_HEADER_BYTES)
                if (!readFullyOrNull(header)) return false
                val length: Long
                val type: String
                if (lengthComesFirst) {
                    length = header.readUInt32BE(0)
                    type = String(header, 4, 4, Charsets.US_ASCII)
                } else {
                    type = String(header, 0, 4, Charsets.US_ASCII)
                    length = header.readUInt32LE(4)
                }
                if (matches(type)) return true
                if (type == stopAt) return false
                // PNG chunks carry a four-byte CRC; RIFF chunks are padded to an even length.
                val skip = if (lengthComesFirst) length + 4 else length + (length and 1L)
                if (skip < 0 || skip > MAX_SCAN_BYTES || !skipFully(skip)) return false
                scanned += CHUNK_HEADER_BYTES + skip.toInt()
            }
            return false
        }

        private fun InputStream.readFullyOrNull(into: ByteArray): Boolean {
            var read = 0
            while (read < into.size) {
                val count = read(into, read, into.size - read)
                if (count < 0) return false
                read += count
            }
            return true
        }

        private fun InputStream.skipFully(count: Long): Boolean {
            var remaining = count
            while (remaining > 0) {
                val skipped = skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else {
                    // skip() is allowed to do nothing without being at the end of the stream.
                    if (read() < 0) return false
                    remaining--
                }
            }
            return true
        }

        private fun ByteArray.readUInt32BE(at: Int): Long {
            var value = 0L
            for (index in 0 until 4) value = (value shl 8) or (this[at + index].toLong() and 0xFF)
            return value
        }

        private fun ByteArray.readUInt32LE(at: Int): Long {
            var value = 0L
            for (index in 3 downTo 0) value = (value shl 8) or (this[at + index].toLong() and 0xFF)
            return value
        }

        private fun ByteArray.startsWith(prefix: ByteArray) = regionMatches(prefix, at = 0)

        private fun ByteArray.regionMatches(expected: ByteArray, at: Int): Boolean {
            if (size < at + expected.size) return false
            return expected.indices.all { this[at + it] == expected[it] }
        }

        private const val SIGNATURE_BYTES = 12
        private const val CHUNK_HEADER_BYTES = 8

        /** Enough for any sane header; past that the file is lying about being an animation. */
        private const val MAX_SCAN_BYTES = 512 * 1024
        private const val WEBP_HEADER_CHUNKS = 6

        private val GIF_SIGNATURE = "GIF8".toByteArray(Charsets.US_ASCII)
        private val RIFF_SIGNATURE = "RIFF".toByteArray(Charsets.US_ASCII)
        private val WEBP_SIGNATURE = "WEBP".toByteArray(Charsets.US_ASCII)
        private val PNG_SIGNATURE = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
    }
}
