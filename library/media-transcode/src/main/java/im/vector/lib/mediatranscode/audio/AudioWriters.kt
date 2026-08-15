/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.mediatranscode.audio

import android.content.Context
import androidx.annotation.RequiresApi
import im.vector.lib.mediatranscode.MediaSourceInfo
import im.vector.lib.mediatranscode.MuxableFormats
import im.vector.lib.mediatranscode.SpeedTimeMap
import im.vector.lib.mediatranscode.VideoEditSpec
import timber.log.Timber

/** Picks how the source sound reaches the output: copied through when it can be, re-encoded when not. */
@RequiresApi(18)
internal object AudioWriters {

    /**
     * @param startUs where the output's zero sits in the source, which the remux path can only put
     * on a sync frame.
     * @param timeMap the map the video follows; only consulted for a re-timed export.
     * @return null when there is nothing to write, or the device can neither copy nor encode it.
     */
    fun create(
            context: Context,
            spec: VideoEditSpec,
            source: MediaSourceInfo,
            startUs: Long,
            timeMap: SpeedTimeMap,
    ): AudioTrackWriter? {
        if (spec.muted) return null
        val mime = source.audioMime ?: return null
        if (!spec.isRetimed && !spec.isAmplified) {
            if (MuxableFormats.isMuxableAudio(mime)) {
                AudioTrackCopier.create(context, spec.sourceUri, spec.endUs)?.let { return it }
                Timber.w("VideoEdit: cannot copy the $mime track through, re-encoding it instead")
            } else {
                Timber.i("VideoEdit: an mp4 cannot hold $mime, re-encoding the audio to AAC")
            }
        }
        // A speed change has its own map; anything else is only re-encoded to change container, so
        // its timing must not move.
        return transcode(context, spec, startUs, if (spec.isRetimed) timeMap else SpeedTimeMap(startUs, 1f), mime)
    }

    private fun transcode(
            context: Context,
            spec: VideoEditSpec,
            startUs: Long,
            timeMap: SpeedTimeMap,
            mime: String,
    ): AudioTrackWriter? {
        val transcoder = AudioTrackTranscoder.create(
                context, spec.sourceUri, startUs, spec.endUs, timeMap, spec.changePitch, spec.isRetimed, spec.volume
        )
        if (transcoder == null) {
            Timber.w("VideoEdit: nothing on this device decodes $mime, dropping the audio")
            return null
        }
        // The muxer needs every track's format before it starts, and the encoded one only exists
        // once the encoder has seen some sound. A device with no usable AAC encoder loses its
        // audio rather than the whole export.
        val primed = runCatching { transcoder.prime() }
                .onFailure { Timber.w(it, "VideoEdit: cannot re-encode the $mime track, dropping it") }
                .getOrDefault(false)
        if (primed) {
            Timber.d("VideoEdit: re-encoding the $mime track to AAC at ${timeMap.rate}x")
            return transcoder
        }
        Timber.w("VideoEdit: the AAC encoder took nothing from the $mime track, dropping the audio")
        transcoder.release()
        return null
    }
}
