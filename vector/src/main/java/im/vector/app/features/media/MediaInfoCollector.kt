/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import im.vector.app.core.utils.TextUtils
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageStickerContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.threeten.bp.Duration
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlin.math.roundToInt

sealed class MediaSource {
    data class LocalFile(val file: File) : MediaSource()
    data class ContentUri(val uri: Uri) : MediaSource()
}

/**
 * Media details for the viewer's info sheet: what the event claims, then what the file itself says.
 */
object MediaInfoCollector {

    private data class DeclaredInfo(
            val mimeType: String?,
            val width: Int,
            val height: Int,
            val size: Long,
            val durationMs: Long?,
    )

    fun fromEvent(context: Context, data: AttachmentData, event: TimelineEvent?): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        data.filename.takeIf { it.isNotBlank() }?.let {
            fields[context.getString(CommonStrings.media_info_name)] = it.neutralizeDirectionOverrides()
        }

        val content = event?.root?.getClearContent()
        val declared = when (val message = content.toModel<MessageContent>() ?: content.toModel<MessageStickerContent>()) {
            is MessageImageContent -> message.info?.let { DeclaredInfo(it.mimeType, it.width, it.height, it.size, null) }
            is MessageStickerContent -> message.info?.let { DeclaredInfo(it.mimeType, it.width, it.height, it.size, null) }
            is MessageVideoContent -> message.videoInfo?.let { DeclaredInfo(it.mimeType, it.width, it.height, it.size, it.duration.toLong()) }
            else -> null
        }

        val width = declared?.width ?: (data as? ImageContentRenderer.Data)?.width
        val height = declared?.height ?: (data as? ImageContentRenderer.Data)?.height
        val durationMs = declared?.durationMs ?: (data as? VideoContentRenderer.Data)?.durationMs

        (declared?.mimeType ?: data.mimeType)?.let { fields[context.getString(CommonStrings.media_info_type)] = it }
        declared?.size?.takeIf { it > 0 }?.let { fields[context.getString(CommonStrings.media_info_size)] = TextUtils.formatFileSize(context, it) }
        putResolution(context, fields, width, height)
        durationMs?.takeIf { it > 0 }?.let {
            fields[context.getString(CommonStrings.media_info_duration)] = TextUtils.formatDuration(Duration.ofMillis(it))
        }
        return fields
    }

    fun probe(context: Context, source: MediaSource, isVideo: Boolean): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        source.sizeBytes(context)?.takeIf { it > 0 }?.let {
            fields[context.getString(CommonStrings.media_info_size)] = TextUtils.formatFileSize(context, it)
        }
        if (isVideo) probeVideo(context, source, fields) else probeImage(context, source, fields)
        return fields
    }

    private fun probeImage(context: Context, source: MediaSource, fields: MutableMap<String, String>) {
        runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            source.openStream(context)?.use { BitmapFactory.decodeStream(it, null, options) }
            options.outMimeType?.let { fields[context.getString(CommonStrings.media_info_type)] = it }
            putResolution(context, fields, options.outWidth, options.outHeight)
        }
        runCatching {
            val exif = source.openStream(context)?.use { ExifInterface(it) } ?: return@runCatching
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let {
                fields[context.getString(CommonStrings.media_info_date_taken)] = it.asReadableExifDate()
            }
            val camera = listOfNotNull(
                    exif.getAttribute(ExifInterface.TAG_MAKE),
                    exif.getAttribute(ExifInterface.TAG_MODEL)
            ).joinToString(" ").trim()
            if (camera.isNotEmpty()) fields[context.getString(CommonStrings.media_info_camera)] = camera
            exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.takeIf { it.isNotBlank() }?.let {
                fields[context.getString(CommonStrings.media_info_lens)] = it
            }
            exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let {
                fields[context.getString(CommonStrings.media_info_aperture)] = context.getString(CommonStrings.media_info_aperture_value, it.trimZeros())
            }
            exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let { seconds ->
                fields[context.getString(CommonStrings.media_info_exposure)] = if (seconds < 1.0) {
                    context.getString(CommonStrings.media_info_exposure_fraction_value, (1.0 / seconds).roundToInt())
                } else {
                    context.getString(CommonStrings.media_info_exposure_value, seconds.trimZeros())
                }
            }
            exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 }?.let {
                fields[context.getString(CommonStrings.media_info_iso)] = it.toString()
            }
            exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let {
                fields[context.getString(CommonStrings.media_info_focal_length)] =
                        context.getString(CommonStrings.media_info_focal_length_value, it.trimZeros())
            }
            exif.rotationDegrees.takeIf { it != 0 }?.let {
                fields[context.getString(CommonStrings.media_info_rotation)] = context.getString(CommonStrings.media_info_rotation_value, it)
            }
            exif.latLong?.let {
                fields[context.getString(CommonStrings.media_info_location)] = String.format(Locale.US, "%.5f, %.5f", it[0], it[1])
            }
        }
    }

    private fun probeVideo(context: Context, source: MediaSource, fields: MutableMap<String, String>) {
        val retriever = MediaMetadataRetriever()
        runCatching {
            source.applyTo(retriever, context)
            val width = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            putResolution(context, fields, width, height)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }?.let {
                fields[context.getString(CommonStrings.media_info_duration)] = TextUtils.formatDuration(Duration.ofMillis(it))
            }
            retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.takeIf { it > 0 }?.let {
                fields[context.getString(CommonStrings.media_info_bitrate)] = context.getString(CommonStrings.media_info_bitrate_value, it / 1000)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                retriever.intMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.takeIf { it != 0 }?.let {
                    fields[context.getString(CommonStrings.media_info_rotation)] = context.getString(CommonStrings.media_info_rotation_value, it)
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.takeIf { it.isNotBlank() }?.let {
                    fields[context.getString(CommonStrings.media_info_location)] = it
                }
            }
        }
        runCatching { retriever.release() }

        // Codecs and the declared frame rate are only in the track formats, which need MediaExtractor.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            runCatching { probeTracks(context, source, fields) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
    private fun probeTracks(context: Context, source: MediaSource, fields: MutableMap<String, String>) {
        val extractor = MediaExtractor()
        try {
            source.applyTo(extractor, context)
            for (track in 0 until extractor.trackCount) {
                val format = runCatching { extractor.getTrackFormat(track) }.getOrNull() ?: continue
                val mimeType = runCatching { format.getString(MediaFormat.KEY_MIME) }.getOrNull() ?: continue
                when {
                    mimeType.startsWith("video/") -> {
                        fields[context.getString(CommonStrings.media_info_video_codec)] = mimeType
                        format.number(MediaFormat.KEY_FRAME_RATE)?.takeIf { it > 0 }?.let {
                            fields[context.getString(CommonStrings.media_info_frame_rate)] =
                                    context.getString(CommonStrings.media_info_frame_rate_value, it.trimZeros())
                        }
                    }
                    mimeType.startsWith("audio/") -> {
                        fields[context.getString(CommonStrings.media_info_audio_codec)] = mimeType
                        format.number(MediaFormat.KEY_SAMPLE_RATE)?.takeIf { it > 0 }?.let {
                            fields[context.getString(CommonStrings.media_info_sample_rate)] =
                                    context.getString(CommonStrings.media_info_sample_rate_value, it.toInt())
                        }
                        format.number(MediaFormat.KEY_CHANNEL_COUNT)?.takeIf { it > 0 }?.let {
                            fields[context.getString(CommonStrings.media_info_channels)] = it.toInt().toString()
                        }
                    }
                }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun putResolution(context: Context, fields: MutableMap<String, String>, width: Int?, height: Int?) {
        if (width == null || height == null || width <= 0 || height <= 0) return
        fields[context.getString(CommonStrings.media_info_resolution)] = context.getString(CommonStrings.media_info_resolution_value, width, height)
    }

    private fun MediaMetadataRetriever.intMetadata(key: Int) = extractMetadata(key)?.toIntOrNull()

    /** A format value may be stored as either an integer or a float, and asking for the wrong one throws. */
    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
    private fun MediaFormat.number(key: String): Double? =
            runCatching { getInteger(key).toDouble() }.recoverCatching { getFloat(key).toDouble() }.getOrNull()

    private fun Double.trimZeros(): String = if (this % 1.0 == 0.0) toLong().toString() else String.format(Locale.US, "%.2f", this).trimEnd('0').trimEnd('.')

    /** EXIF dates are "yyyy:MM:dd HH:mm:ss"; only the date part uses colons as separators. */
    private fun String.asReadableExifDate(): String {
        val parts = split(" ")
        return if (parts.size == 2) "${parts[0].replace(':', '-')} ${parts[1]}" else this
    }

    private fun MediaSource.openStream(context: Context): InputStream? = when (this) {
        is MediaSource.LocalFile -> file.inputStream()
        is MediaSource.ContentUri -> context.contentResolver.openInputStream(uri)
    }

    private fun MediaSource.sizeBytes(context: Context): Long? = when (this) {
        is MediaSource.LocalFile -> file.length()
        // AssetFileDescriptor only became Closeable at API 19, so no use {} here.
        is MediaSource.ContentUri -> runCatching {
            val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
            try {
                descriptor?.length
            } finally {
                descriptor?.close()
            }
        }.getOrNull()
    }

    private fun MediaSource.applyTo(retriever: MediaMetadataRetriever, context: Context) = when (this) {
        is MediaSource.LocalFile -> retriever.setDataSource(file.absolutePath)
        is MediaSource.ContentUri -> retriever.setDataSource(context, uri)
    }

    @RequiresApi(Build.VERSION_CODES.JELLY_BEAN)
    private fun MediaSource.applyTo(extractor: MediaExtractor, context: Context) = when (this) {
        is MediaSource.LocalFile -> extractor.setDataSource(file.absolutePath)
        is MediaSource.ContentUri -> extractor.setDataSource(context, uri, null)
    }
}
