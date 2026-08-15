/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.listeners.ProgressListener
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * Takes the identifying metadata off a video: where it was shot and what shot it. An mp4 has those
 * boxes blanked in place by [Mp4MetadataScrubber], which leaves the title, artist and artwork it may
 * also carry exactly where they are. Anything else is re-muxed into a fresh MP4 container, copying
 * the sample data verbatim (no re-encode, so quality is untouched) but losing every box with it;
 * display orientation is carried over via the muxer's rotation hint.
 *
 * [MediaMuxer] requires API 18; below that the video is left unchanged, since stripping would
 * otherwise require a full re-encode. Videos on such old devices essentially never carry a location
 * atom, so this degradation is deliberate. The MediaMuxer work lives in [Api18VideoRemuxer] so that
 * class — and its references to API 18 framework types — is only loaded past the version gate.
 */
internal class VideoMetadataStripper @Inject constructor(
        private val context: Context,
        private val temporaryFileCreator: TemporaryFileCreator,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
) {

    /** @return the scrubbed file, or `null` if stripping is unsupported or failed (keep the original). */
    suspend fun strip(videoFile: File, progressListener: ProgressListener? = null): File? =
            withContext(coroutineDispatchers.io) {
                // An mp4 gives up its location and camera without giving up anything else; only a
                // container this cannot read is worth rebuilding wholesale.
                when (Mp4MetadataScrubber.scrub(videoFile)) {
                    Mp4MetadataScrubber.Outcome.SCRUBBED -> return@withContext videoFile
                    Mp4MetadataScrubber.Outcome.NOTHING_TO_STRIP -> return@withContext videoFile
                    Mp4MetadataScrubber.Outcome.UNSUPPORTED -> Unit
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return@withContext null
                Api18VideoRemuxer.remux(VideoSource.OfFile(videoFile), temporaryFileCreator.create(), progressListener)
            }

    /**
     * Re-mux straight from the picked content URI. The extractor reads the source itself, so the whole
     * video never has to be copied to a working file first — one pass over it instead of two.
     */
    suspend fun strip(sourceUri: Uri, progressListener: ProgressListener? = null): File? =
            withContext(coroutineDispatchers.io) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) return@withContext null
                Api18VideoRemuxer.remux(VideoSource.OfUri(context, sourceUri), temporaryFileCreator.create(), progressListener)
            }

    /**
     * The working copy the caller is about to upload, scrubbed where the container allows it.
     * @return true when the file was understood, whether or not it had anything to hide.
     */
    suspend fun stripInPlace(file: File): Boolean = withContext(coroutineDispatchers.io) {
        Mp4MetadataScrubber.scrub(file) != Mp4MetadataScrubber.Outcome.UNSUPPORTED
    }
}

/** Where the samples are read from; the muxing itself is identical either way. */
private sealed interface VideoSource {
    fun applyTo(extractor: MediaExtractor)
    fun applyTo(retriever: MediaMetadataRetriever)

    class OfFile(private val file: File) : VideoSource {
        override fun applyTo(extractor: MediaExtractor) = extractor.setDataSource(file.absolutePath)
        override fun applyTo(retriever: MediaMetadataRetriever) = retriever.setDataSource(file.absolutePath)
    }

    class OfUri(private val context: Context, private val uri: Uri) : VideoSource {
        override fun applyTo(extractor: MediaExtractor) = extractor.setDataSource(context, uri, null)
        override fun applyTo(retriever: MediaMetadataRetriever) = retriever.setDataSource(context, uri)
    }
}

@RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
private object Api18VideoRemuxer {

    fun remux(source: VideoSource, output: File, progressListener: ProgressListener?): File? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var success = false
        try {
            source.applyTo(extractor)
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

            val durationUs = readDurationUs(source)
            readRotation(source)?.let { muxer.setOrientationHint(it) }
            muxer.start()

            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var lastProgress = -1
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
                if (progressListener != null && durationUs > 0) {
                    // Rewriting the container is a full pass over a possibly huge file; without this the
                    // send just sits there looking stuck. Reported in permille and only on change, since
                    // there is one sample every few ms and each callback hops to the main thread.
                    val progress = (bufferInfo.presentationTimeUs * PROGRESS_TOTAL / durationUs)
                            .toInt()
                            .coerceIn(0, PROGRESS_TOTAL)
                    if (progress != lastProgress) {
                        lastProgress = progress
                        progressListener.onProgress(progress, PROGRESS_TOTAL)
                    }
                }
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

    private fun readRotation(source: VideoSource): Int? =
            readMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?.takeIf { it != 0 }

    private fun readDurationUs(source: VideoSource): Long =
            (readMetadata(source, MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L

    private fun readMetadata(source: VideoSource, key: Int): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            source.applyTo(retriever)
            retriever.extractMetadata(key)
        } catch (t: Throwable) {
            null
        } finally {
            tryOrNull { retriever.release() }
        }
    }

    private const val DEFAULT_BUFFER_SIZE = 1 shl 20 // 1 MB
    private const val PROGRESS_TOTAL = 1000
}
