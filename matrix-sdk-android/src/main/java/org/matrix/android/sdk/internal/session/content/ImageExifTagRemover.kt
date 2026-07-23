/*
 * Copyright 2021 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
internal class ImageExifTagRemover @Inject constructor(
        private val temporaryFileCreator: TemporaryFileCreator,
        private val coroutineDispatchers: MatrixCoroutineDispatchers
) {

    /**
     * @return the scrubbed file (or the original when there is nothing to strip / scrubbing failed),
     * or `null` if the format can't be stripped in place and should be re-encoded instead.
     */
    suspend fun stripImageMetadata(imageFile: File): File? = withContext(coroutineDispatchers.io) {
        when (sniff(imageFile)) {
            Format.JPEG -> stripJpeg(imageFile)
            Format.PNG,
            Format.WEBP -> stripWithExifInterface(imageFile)
            Format.GIF -> imageFile // no EXIF
            Format.OTHER -> null // HEIC/HEIF/TIFF/RAW/unknown -> caller re-encodes
        }
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

    private enum class Format { JPEG, PNG, WEBP, GIF, OTHER }

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
            else -> Format.OTHER
        }
    }

    companion object {
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
