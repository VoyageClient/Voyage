/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.withContext
import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject

/**
 * Strips EXIF/metadata from image files before upload, keeping only the display orientation.
 * Everything else is dropped: GPS location, capture timestamps, camera make/model & serial numbers,
 * maker notes, XMP, and (for JPEG) the embedded EXIF thumbnail which can carry its own GPS copy.
 *
 * JPEG is rewritten losslessly via Commons Imaging; PNG and WebP via androidx [ExifInterface]. GIF
 * has no EXIF to strip. Formats that can't be scrubbed in place (HEIC/HEIF, TIFF, RAW, …) return
 * `null` so the caller can re-encode them instead.
 */
internal class AndroidImageExifTagRemover @Inject constructor(
        private val temporaryFileCreator: TemporaryFileCreator,
        private val coroutineDispatchers: MatrixCoroutineDispatchers
) : ImageExifTagRemover {

    /**
     * @return the scrubbed file (or the original when there is nothing to strip / scrubbing failed),
     * or `null` if the format can't be stripped in place and should be re-encoded instead.
     */
    override suspend fun stripImageMetadata(imageFile: File): File? = withContext(coroutineDispatchers.io) {
        when (sniff(imageFile)) {
            Format.JPEG -> stripJpeg(imageFile)
            Format.PNG,
            Format.WEBP -> stripWithExifInterface(imageFile)
            Format.GIF -> imageFile // no EXIF
            // A bare codestream has nowhere to put metadata. The container can hold Exif/XMP boxes,
            // and rather than rewrite the box structure we only fall back to re-encoding when one is
            // actually present — so the common case still uploads the original bytes untouched.
            Format.JXL -> if (jxlCarriesMetadata(imageFile)) null else imageFile
            Format.OTHER -> null // HEIC/HEIF/TIFF/RAW/unknown -> caller re-encodes
        }
    }

    private fun jxlCarriesMetadata(file: File): Boolean {
        val head = ByteArray(12)
        val read = tryOrNull { file.inputStream().use { it.read(head) } } ?: 0
        // Bare codestream: no box structure at all.
        if (read >= 2 && head[0] == 0xFF.toByte() && head[1] == 0x0A.toByte()) return false
        return tryOrNull {
            file.inputStream().buffered().use { input ->
                val header = ByteArray(8)
                var guard = 0
                while (guard++ < MAX_BOXES_SCANNED) {
                    var read8 = 0
                    while (read8 < 8) {
                        val n = input.read(header, read8, 8 - read8)
                        if (n <= 0) return@use false
                        read8 += n
                    }
                    val type = String(header, 4, 4, Charsets.US_ASCII)
                    if (type in METADATA_BOX_TYPES) return@use true
                    val size = ((header[0].toLong() and 0xFF) shl 24) or ((header[1].toLong() and 0xFF) shl 16) or
                            ((header[2].toLong() and 0xFF) shl 8) or (header[3].toLong() and 0xFF)
                    val skip = when (size) {
                        0L -> return@use false // extends to EOF
                        1L -> return@use true // 64-bit size: unparsed here, assume the worst
                        else -> size - 8
                    }
                    if (skip < 0 || !input.skipFully(skip)) return@use false
                }
                // Ran out of budget without settling it; treat as metadata-bearing.
                true
            }
        } ?: true
    }

    /** [InputStream.skip] is allowed to skip less than asked without being at EOF. */
    private fun InputStream.skipFully(count: Long): Boolean {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() < 0) {
                return false
            } else {
                remaining--
            }
        }
        return true
    }

    private suspend fun stripJpeg(jpegFile: File): File {
        // Rebuild the EXIF block from an empty set holding only orientation. A denylist of
        // "sensitive" tags always lags newly-defined ones; keeping solely what we need is leak-proof.
        val orientation = readJpegOrientation(jpegFile)
        val minimalSet = orientation?.let {
            tryOrNull("Unable to build scrubbed exif") {
                TiffOutputSet().apply { getOrCreateRootDirectory().add(TiffTagConstants.TIFF_TAG_ORIENTATION, it) }
            }
        }

        val scrubbedFile = temporaryFileCreator.create()
        return runCatching {
            FileOutputStream(scrubbedFile).use { fos ->
                BufferedOutputStream(fos).use { out ->
                    if (minimalSet != null) {
                        ExifRewriter().updateExifMetadataLossless(jpegFile, out, minimalSet)
                    } else {
                        // No orientation to preserve; drop the whole EXIF segment instead.
                        ExifRewriter().removeExifMetadata(jpegFile, out)
                    }
                }
            }
        }.fold(
                onSuccess = { scrubbedFile },
                onFailure = {
                    Timber.w(it, "Failed to strip jpeg exif; uploading original")
                    scrubbedFile.delete()
                    jpegFile
                }
        )
    }

    // ExifInterface can rewrite PNG/WebP EXIF but not remove an embedded thumbnail; PNG/WebP almost
    // never carry one, so the GPS/identifying tags below are what matters. Skip the rewrite entirely
    // when none of them are present (the common case: screenshots etc.) so we never needlessly touch
    // the file.
    private suspend fun stripWithExifInterface(imageFile: File): File {
        val hasSensitiveTags = tryOrNull {
            val exif = ExifInterface(imageFile.absolutePath)
            SENSITIVE_TAGS.any { exif.hasAttribute(it) }
        } ?: false
        if (!hasSensitiveTags) return imageFile

        val scrubbedFile = temporaryFileCreator.create()
        imageFile.copyTo(scrubbedFile, overwrite = true)
        return runCatching {
            val exif = ExifInterface(scrubbedFile.absolutePath)
            SENSITIVE_TAGS.forEach { exif.setAttribute(it, null) }
            exif.saveAttributes()
            scrubbedFile
        }.getOrElse {
            Timber.w(it, "Failed to strip exif via ExifInterface; uploading original")
            scrubbedFile.delete()
            imageFile
        }
    }

    private fun readJpegOrientation(jpegFile: File): Short? = tryOrNull("No exif orientation") {
        (Imaging.getMetadata(jpegFile) as? JpegImageMetadata)
                ?.exif
                ?.findField(TiffTagConstants.TIFF_TAG_ORIENTATION)
                ?.intValue
                ?.toShort()
    }

    private enum class Format { JPEG, PNG, WEBP, GIF, JXL, OTHER }

    private fun sniff(file: File): Format {
        val head = ByteArray(12)
        val read = tryOrNull { file.inputStream().use { it.read(head) } } ?: 0
        if (read < 12) return Format.OTHER
        return when {
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> Format.JPEG
            head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() -> Format.PNG
            head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() -> Format.GIF
            head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
                    head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() && head[10] == 'B'.code.toByte() && head[11] == 'P'.code.toByte() -> Format.WEBP
            isJxlSignature(head, read) -> Format.JXL
            else -> Format.OTHER
        }
    }

    companion object {
        private val METADATA_BOX_TYPES = setOf("Exif", "xml ", "jumb")
        private const val MAX_BOXES_SCANNED = 64

        // GPS + all directly-identifying/tracking EXIF tags. Orientation is deliberately kept.
        private val SENSITIVE_TAGS = listOf(
                ExifInterface.TAG_GPS_ALTITUDE,
                ExifInterface.TAG_GPS_ALTITUDE_REF,
                ExifInterface.TAG_GPS_AREA_INFORMATION,
                ExifInterface.TAG_GPS_DATESTAMP,
                ExifInterface.TAG_GPS_DEST_BEARING,
                ExifInterface.TAG_GPS_DEST_BEARING_REF,
                ExifInterface.TAG_GPS_DEST_DISTANCE,
                ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
                ExifInterface.TAG_GPS_DEST_LATITUDE,
                ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
                ExifInterface.TAG_GPS_DEST_LONGITUDE,
                ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
                ExifInterface.TAG_GPS_DIFFERENTIAL,
                ExifInterface.TAG_GPS_DOP,
                ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
                ExifInterface.TAG_GPS_IMG_DIRECTION,
                ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LATITUDE_REF,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                ExifInterface.TAG_GPS_MAP_DATUM,
                ExifInterface.TAG_GPS_MEASURE_MODE,
                ExifInterface.TAG_GPS_PROCESSING_METHOD,
                ExifInterface.TAG_GPS_SATELLITES,
                ExifInterface.TAG_GPS_SPEED,
                ExifInterface.TAG_GPS_SPEED_REF,
                ExifInterface.TAG_GPS_STATUS,
                ExifInterface.TAG_GPS_TIMESTAMP,
                ExifInterface.TAG_GPS_TRACK,
                ExifInterface.TAG_GPS_TRACK_REF,
                ExifInterface.TAG_GPS_VERSION_ID,
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_DATETIME_DIGITIZED,
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_SUBSEC_TIME,
                ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
                ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_SOFTWARE,
                ExifInterface.TAG_MAKER_NOTE,
                ExifInterface.TAG_ARTIST,
                ExifInterface.TAG_COPYRIGHT,
                ExifInterface.TAG_CAMERA_OWNER_NAME,
                ExifInterface.TAG_BODY_SERIAL_NUMBER,
                ExifInterface.TAG_LENS_MAKE,
                ExifInterface.TAG_LENS_MODEL,
                ExifInterface.TAG_LENS_SERIAL_NUMBER,
                ExifInterface.TAG_LENS_SPECIFICATION,
                ExifInterface.TAG_IMAGE_DESCRIPTION,
                ExifInterface.TAG_IMAGE_UNIQUE_ID,
                ExifInterface.TAG_USER_COMMENT,
                ExifInterface.TAG_XMP,
        )
    }
}
