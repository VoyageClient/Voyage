/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * Re-muxes a video into a fresh MP4 container, copying the audio/video sample data verbatim (no
 * re-encode, so quality is untouched) while dropping the source's metadata atoms — GPS location,
 * capturing device, author, etc. Display orientation is carried over via the muxer's rotation hint.
 *
 * [MediaMuxer] requires API 18; below that the video is left unchanged, since stripping would
 * otherwise require a full re-encode. Videos on such old devices essentially never carry a location
 * atom, so this degradation is deliberate. The MediaMuxer work lives in [Api18VideoRemuxer] so that
 * class — and its references to API 18 framework types — is only loaded past the version gate.
 */
internal class VideoMetadataStripper @Inject constructor(
        private val temporaryFileCreator: TemporaryFileCreator,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
) {

    /** @return the re-muxed file, or `null` if stripping is unsupported or failed (keep the original). */
    suspend fun strip(videoFile: File): File? = withContext(coroutineDispatchers.io) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return@withContext null
        Api18VideoRemuxer.remux(videoFile, temporaryFileCreator.create())
    }
}

@RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
private object Api18VideoRemuxer {

    fun remux(videoFile: File, output: File): File? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var success = false
        try {
            extractor.setDataSource(videoFile.absolutePath)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val indexMap = HashMap<Int, Int>()
            var bufferSize = DEFAULT_BUFFER_SIZE
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                // addTrack throws for codecs the MP4 muxer can't hold (e.g. VP8/VP9); let it abort.
                indexMap[i] = muxer.addTrack(format)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                extractor.selectTrack(i)
            }
            check(indexMap.isNotEmpty()) { "No tracks to mux" }

            readRotation(videoFile)?.let { muxer.setOrientationHint(it) }
            muxer.start()

            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                bufferInfo.offset = 0
                bufferInfo.size = size
                bufferInfo.presentationTimeUs = extractor.sampleTime
                @Suppress("DEPRECATION")
                bufferInfo.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_SYNC_FRAME
                } else {
                    0
                }
                muxer.writeSampleData(indexMap.getValue(extractor.sampleTrackIndex), buffer, bufferInfo)
                extractor.advance()
            }
            muxer.stop()
            success = true
        } catch (t: Throwable) {
            Timber.w(t, "Video metadata strip failed; keeping original")
        } finally {
            tryOrNull { muxer?.release() }
            tryOrNull { extractor.release() }
        }

        return if (success) {
            output
        } else {
            output.delete()
            null
        }
    }

    private fun readRotation(videoFile: File): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?.takeIf { it != 0 }
        } catch (t: Throwable) {
            null
        } finally {
            tryOrNull { retriever.release() }
        }
    }

    private const val DEFAULT_BUFFER_SIZE = 1 shl 20 // 1 MB
}
