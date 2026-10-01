/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.animatedimage

class WebmFrame(val timeUs: Long, val data: ByteArray, val alpha: ByteArray?)

class WebmVideo(
        val codecMime: String,
        val width: Int,
        val height: Int,
        val frames: List<WebmFrame>,
) {
    val hasAlpha: Boolean get() = frames.any { it.alpha != null }

    /** Display duration of frame [index], from the next frame's timestamp (the last repeats the previous gap). */
    fun durationUs(index: Int): Long {
        if (frames.size < 2) return DEFAULT_FRAME_US
        val next = if (index + 1 < frames.size) index + 1 else index
        val prev = next - 1
        return (frames[next].timeUs - frames[prev].timeUs).takeIf { it > 0 } ?: DEFAULT_FRAME_US
    }

    private companion object {
        const val DEFAULT_FRAME_US = 33_333L
    }
}

/**
 * Just enough Matroska/WebM to read a short single-track VP8/VP9 clip, including the per-block alpha
 * stream (BlockAdditional, BlockAddID 1) that MediaExtractor drops. Reads the whole file from memory.
 */
object WebmDemuxer {

    fun parse(bytes: ByteArray): WebmVideo? {
        val state = State(bytes)
        return try {
            state.parseChildren(0, bytes.size)
            state.result()
        } catch (truncated: RuntimeException) {
            // Out-of-range offsets from a cut-off file: keep whatever frames were read before it.
            state.result()
        }
    }

    private class Track(var number: Long = -1, var type: Long = -1, var codecId: String? = null, var width: Int = 0, var height: Int = 0)

    private class State(val bytes: ByteArray) {
        var timecodeScaleNs = 1_000_000L
        val tracks = mutableListOf<Track>()
        var clusterTimecode = 0L
        val frames = mutableListOf<WebmFrame>()
        var videoTrack: Track? = null

        // Block parse state inside a BlockGroup, flushed when the group ends.
        var groupBlock: ByteArray? = null
        var groupAdditional: ByteArray? = null
        var currentAddId = 1L

        fun result(): WebmVideo? {
            val track = videoTrack ?: return null
            val mime = when (track.codecId) {
                "V_VP9" -> "video/x-vnd.on2.vp9"
                "V_VP8" -> "video/x-vnd.on2.vp8"
                else -> return null
            }
            if (track.width <= 0 || track.height <= 0 || frames.isEmpty()) return null
            return WebmVideo(mime, track.width, track.height, frames.sortedBy { it.timeUs })
        }

        fun parseChildren(start: Int, end: Int) {
            var pos = start
            while (pos < end) {
                val (id, idLen) = readId(pos) ?: return
                val (size, sizeLen) = readSize(pos + idLen) ?: return
                val dataStart = pos + idLen + sizeLen
                if (dataStart > end) return
                val dataEnd = if (size < 0) end else (dataStart + size).coerceAtMost(end.toLong()).toInt()
                handle(id, dataStart, dataEnd)
                pos = dataEnd
            }
        }

        private fun handle(id: Long, start: Int, end: Int) {
            when (id) {
                ID_SEGMENT, ID_TRACKS, ID_VIDEO, ID_BLOCK_ADDITIONS -> parseChildren(start, end)
                ID_CLUSTER -> {
                    clusterTimecode = 0
                    parseChildren(start, end)
                }
                ID_INFO -> parseChildren(start, end)
                ID_TIMECODE_SCALE -> timecodeScaleNs = readUInt(start, end).takeIf { it > 0 } ?: timecodeScaleNs
                ID_TRACK_ENTRY -> {
                    val track = Track()
                    tracks += track
                    parseChildren(start, end)
                    if (track.type == 1L && videoTrack == null) videoTrack = track
                }
                ID_TRACK_NUMBER -> tracks.lastOrNull()?.number = readUInt(start, end)
                ID_TRACK_TYPE -> tracks.lastOrNull()?.type = readUInt(start, end)
                ID_CODEC_ID -> tracks.lastOrNull()?.codecId = String(bytes, start, end - start, Charsets.US_ASCII).trimEnd('\u0000')
                ID_PIXEL_WIDTH -> tracks.lastOrNull()?.width = readUInt(start, end).toInt()
                ID_PIXEL_HEIGHT -> tracks.lastOrNull()?.height = readUInt(start, end).toInt()
                ID_CLUSTER_TIMECODE -> clusterTimecode = readUInt(start, end)
                ID_SIMPLE_BLOCK -> addBlock(bytes.copyOfRange(start, end), null)
                ID_BLOCK_GROUP -> {
                    groupBlock = null
                    groupAdditional = null
                    parseChildren(start, end)
                    groupBlock?.let { addBlock(it, groupAdditional) }
                }
                ID_BLOCK -> groupBlock = bytes.copyOfRange(start, end)
                ID_BLOCK_MORE -> {
                    currentAddId = 1
                    parseChildren(start, end)
                }
                ID_BLOCK_ADD_ID -> currentAddId = readUInt(start, end)
                ID_BLOCK_ADDITIONAL -> if (currentAddId == 1L) groupAdditional = bytes.copyOfRange(start, end)
                else -> Unit
            }
        }

        private fun addBlock(block: ByteArray, alpha: ByteArray?) {
            val track = videoTrack ?: tracks.firstOrNull { it.type == 1L } ?: return
            var pos = 0
            val trackNumber = readVint(block, pos, stripMarker = true) ?: return
            pos += trackNumber.second
            if (trackNumber.first != track.number) return
            if (pos + 3 > block.size) return
            val relative = ((block[pos].toInt() shl 8) or (block[pos + 1].toInt() and 0xFF)).toShort().toLong()
            val flags = block[pos + 2].toInt() and 0xFF
            pos += 3
            // Lacing is never used for video in practice; skip rather than mis-split.
            if (flags and 0x06 != 0) return
            val timeUs = (clusterTimecode + relative) * timecodeScaleNs / 1000
            frames += WebmFrame(timeUs, block.copyOfRange(pos, block.size), alpha?.takeIf { it.isNotEmpty() })
        }

        private fun readId(pos: Int): Pair<Long, Int>? = readVint(bytes, pos, stripMarker = false)

        // Unknown size (all value bits set) comes back as -1.
        private fun readSize(pos: Int): Pair<Long, Int>? {
            val (value, length) = readVint(bytes, pos, stripMarker = true) ?: return null
            val allOnes = (1L shl (7 * length)) - 1
            return (if (value == allOnes) -1L else value) to length
        }

        private fun readUInt(start: Int, end: Int): Long {
            var value = 0L
            for (i in start until end.coerceAtMost(start + 8)) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
            return value
        }
    }

    internal fun readVint(source: ByteArray, pos: Int, stripMarker: Boolean): Pair<Long, Int>? {
        if (pos >= source.size) return null
        val first = source[pos].toInt() and 0xFF
        if (first == 0) return null
        val length = Integer.numberOfLeadingZeros(first) - 23
        if (pos + length > source.size) return null
        var value = if (stripMarker) (first and (0xFF ushr length)).toLong() else first.toLong()
        for (i in 1 until length) value = (value shl 8) or (source[pos + i].toLong() and 0xFF)
        return value to length
    }

    private const val ID_SEGMENT = 0x18538067L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMECODE_SCALE = 0x2AD7B1L
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_VIDEO = 0xE0L
    private const val ID_PIXEL_WIDTH = 0xB0L
    private const val ID_PIXEL_HEIGHT = 0xBAL
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_CLUSTER_TIMECODE = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_BLOCK_ADDITIONS = 0x75A1L
    private const val ID_BLOCK_MORE = 0xA6L
    private const val ID_BLOCK_ADD_ID = 0xEEL
    private const val ID_BLOCK_ADDITIONAL = 0xA5L
}
