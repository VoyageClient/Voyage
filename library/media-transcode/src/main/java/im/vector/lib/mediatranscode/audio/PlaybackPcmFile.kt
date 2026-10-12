/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** API 14 has no time-stretching player; process PCM WAVs in bounded chunks instead. */
object PlaybackPcmFile {
    fun supports(file: File): Boolean = runCatching { RandomAccessFile(file, "r").use { readHeader(it) != null } }.getOrDefault(false)

    fun transform(source: File, destination: File, speed: Float, pitch: Float, isActive: () -> Boolean) {
        require(speed in 0.1f..3f && pitch in 0.1f..3f)
        RandomAccessFile(source, "r").use { input ->
            val header = requireNotNull(readHeader(input))
            val stretch = SonicStream(header.sampleRate, header.channels).apply { this.speed = speed / pitch }
            val resample = PcmResampler(header.channels).apply { this.speed = pitch }
            RandomAccessFile(destination, "rw").use { output ->
                output.setLength(0)
                input.seek(header.offset)
                output.writeBytes("RIFF")
                output.writeInt(0)
                output.writeBytes("WAVEfmt ")
                output.writeInt(Integer.reverseBytes(16))
                output.writeShort(java.lang.Short.reverseBytes(1).toInt())
                output.writeShort(java.lang.Short.reverseBytes(header.channels.toShort()).toInt())
                output.writeInt(Integer.reverseBytes(header.sampleRate))
                output.writeInt(Integer.reverseBytes(header.sampleRate * header.channels * 2))
                output.writeShort(java.lang.Short.reverseBytes((header.channels * 2).toShort()).toInt())
                output.writeShort(java.lang.Short.reverseBytes(16).toInt())
                output.writeBytes("data")
                output.writeInt(0)
                var remaining = header.size
                val bytes = ByteArray(8192 * header.channels * 2)
                fun emit() {
                    val samples = ShortArray(stretch.available * header.channels)
                    stretch.read(samples, stretch.available)
                    val result = resample.process(samples, 0, samples.size / header.channels)
                    val encoded = ByteBuffer.allocate(result.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    result.forEach { encoded.putShort(it) }
                    output.write(encoded.array())
                }
                while (remaining > 0) {
                    if (!isActive()) throw InterruptedException("Playback preparation cancelled")
                    val count = minOf(bytes.size.toLong(), remaining).toInt()
                    input.readFully(bytes, 0, count)
                    val shorts = ByteBuffer.wrap(bytes, 0, count).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val samples = ShortArray(count / 2)
                    shorts.get(samples)
                    stretch.write(samples, 0, samples.size / header.channels)
                    emit()
                    remaining -= count
                }
                stretch.endOfStream()
                emit()
                val dataSize = output.length() - 44
                output.seek(40)
                output.writeInt(Integer.reverseBytes(dataSize.toInt()))
                output.seek(4)
                output.writeInt(Integer.reverseBytes((output.length() - 8).toInt()))
            }
        }
    }

    private data class Header(val sampleRate: Int, val channels: Int, val offset: Long, val size: Long)

    private fun readHeader(input: RandomAccessFile): Header? {
        if (input.length() < 44 || input.readInt() != 0x52494646) return null
        input.skipBytes(4)
        if (input.readInt() != 0x57415645) return null
        var rate = 0
        var channels = 0
        while (input.filePointer + 8 <= input.length()) {
            val id = input.readInt()
            val size = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
            val start = input.filePointer
            if (size > input.length() - start) return null
            if (id == 0x666d7420) {
                if (size < 16 || java.lang.Short.reverseBytes(input.readShort()).toInt() != 1) return null
                channels = java.lang.Short.reverseBytes(input.readShort()).toInt()
                rate = Integer.reverseBytes(input.readInt())
                input.skipBytes(6)
                if (java.lang.Short.reverseBytes(input.readShort()).toInt() != 16) return null
            } else if (id == 0x64617461) {
                return Header(rate, channels, start, size).takeIf { rate in 8000..192000 && channels in 1..2 && size % (channels * 2) == 0L }
            }
            input.seek(start + size + size % 2)
        }
        return null
    }
}
