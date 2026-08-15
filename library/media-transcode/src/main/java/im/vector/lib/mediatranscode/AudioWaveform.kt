/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode

import android.content.Context
import android.net.Uri
import androidx.annotation.RequiresApi
import im.vector.lib.mediatranscode.audio.PcmDecoder
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.log10

/** The peaks of an audio file, for drawing it: one per [SLICE_MS], normalised to 0..1. */
object AudioWaveform {

    /** Fine enough for any bar width a phone can show, and cheap to hold for a long recording. */
    const val SLICE_MS = 20

    /**
     * @param onPartial handed everything read so far, every few seconds of sound, so a long file
     * draws as it is decoded rather than after. The peaks it gets are scaled to the loudest heard
     * up to that point, which the final result re-scales to the loudest overall.
     */
    @RequiresApi(18)
    fun extract(
            context: Context,
            source: Uri,
            endUs: Long = Long.MAX_VALUE,
            onPartial: ((FloatArray) -> Unit)? = null,
    ): FloatArray {
        val decoder = PcmDecoder.create(context, source, 0, endUs) ?: return FloatArray(0)
        return try {
            val peaks = ArrayList<Float>()
            var published = 0
            var slicePeak = 0f
            var sliceFrames = 0
            var framesPerSlice = 0

            decoder.decode { samples, count ->
                if (framesPerSlice == 0 && decoder.sampleRate > 0) {
                    framesPerSlice = (decoder.sampleRate * SLICE_MS / 1000).coerceAtLeast(1)
                }
                val channels = decoder.channelCount.coerceAtLeast(1)
                var index = 0
                while (index + channels <= count) {
                    // The loudest channel of the frame, so one silent side does not halve the bar.
                    var frame = 0
                    for (channel in 0 until channels) {
                        frame = maxOf(frame, abs(samples[index + channel].toInt()))
                    }
                    slicePeak = maxOf(slicePeak, frame / FULL_SCALE)
                    index += channels
                    if (++sliceFrames >= framesPerSlice && framesPerSlice > 0) {
                        peaks.add(levelOf(slicePeak))
                        slicePeak = 0f
                        sliceFrames = 0
                    }
                }
                if (onPartial != null && peaks.size - published >= PARTIAL_SLICES) {
                    published = peaks.size
                    onPartial(peaks.toFloatArray())
                }
                true
            }
            if (sliceFrames > 0) peaks.add(levelOf(slicePeak))
            peaks.toFloatArray()
        } catch (error: Exception) {
            Timber.w(error, "AudioWaveform: cannot read $source")
            FloatArray(0)
        } finally {
            decoder.release()
        }
    }

    /**
     * A slice's height comes from how loud it is on its own, not from how it compares with the
     * loudest slice seen so far: scaling against a running maximum redraws every bar each time
     * something louder turns up, and against the whole file it cannot be drawn until it is read.
     *
     * Loudness is read in decibels rather than amplitude, which is both how it is heard and what
     * keeps a quiet recording from drawing as a flat line.
     */
    private fun levelOf(peak: Float): Float {
        if (peak <= 0f) return 0f
        val decibels = 20f * log10(peak)
        return ((decibels - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
    }

    private const val FULL_SCALE = 32_768f

    /** Quieter than this is silence as far as a waveform is concerned. */
    private const val FLOOR_DB = -45f

    /** Five seconds of sound between updates: often enough to look live, rare enough to be free. */
    private const val PARTIAL_SLICES = 5_000 / SLICE_MS
}
