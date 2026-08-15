/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.api.session.content

import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.util.MimeTypes.normalizeMimeType
import org.matrix.android.sdk.internal.di.MoshiProvider
import java.io.Serializable

@JsonClass(generateAdapter = true)
data class ContentAttachmentData(
        val size: Long = 0,
        val duration: Long? = 0,
        val date: Long = 0,
        val height: Long? = 0,
        val width: Long? = 0,
        val exifOrientation: Int = 0, // ExifInterface.ORIENTATION_UNDEFINED
        val name: String? = null,
        /** URI of the content to send, as a string — parse with [queryUriAndroid] on Android. */
        val queryUri: String,
        val mimeType: String?,
        val type: Type,
        val waveform: List<Int>? = null,
        /**
         * How hard the sender asked for this to be squeezed, chosen in the attachment preview.
         * Null leaves the automatic behaviour alone. [compressionQuality] is 0..100.
         */
        /** The sender asked for this one untouched, whatever the send was told to do with the rest. */
        val keepOriginalSize: Boolean = false,
        /** Whether to take the identifying metadata off it, or null to follow the account setting. */
        val stripMetadata: Boolean? = null,
        val compressionQuality: Int? = null,
        val compressionWidth: Int? = null,
        val compressionHeight: Int? = null,
) : Serializable {

    /** True when the sender asked for something the automatic pass would not have done. */
    val hasCustomCompression: Boolean
        get() = compressionQuality != null || compressionWidth != null

    /** The size this will be sent at, so a local echo is laid out at its final shape. */
    val outputWidth: Long? get() = compressionWidth?.toLong() ?: width
    val outputHeight: Long? get() = compressionHeight?.toLong() ?: height

    /**
     * The size as the picture appears, which for a photo carrying an EXIF quarter-turn is not the
     * size stored in the file. Anything the sender is shown or types is in these terms, and so is
     * everything downstream: the compressor rotates before it scales.
     */
    val displayWidth: Long? get() = if (exifOrientation in EXIF_ORIENTATIONS_SWAPPING_SIZE) height else width
    val displayHeight: Long? get() = if (exifOrientation in EXIF_ORIENTATIONS_SWAPPING_SIZE) width else height

    @JsonClass(generateAdapter = false)
    enum class Type {
        FILE,
        IMAGE,
        AUDIO,
        VIDEO,
        VOICE_MESSAGE
    }

    fun getSafeMimeType() = mimeType?.normalizeMimeType()

    fun toJsonString(): String {
        return MoshiProvider.providesMoshi().adapter(ContentAttachmentData::class.java).toJson(this)
    }

    companion object {
        /** TRANSPOSE, ROTATE_90, TRANSVERSE and ROTATE_270: the four that exchange the two sides. */
        val EXIF_ORIENTATIONS_SWAPPING_SIZE = setOf(5, 6, 7, 8)

        fun fromJsonString(json: String): ContentAttachmentData? {
            return MoshiProvider.providesMoshi().adapter(ContentAttachmentData::class.java).fromJson(json)
        }
    }
}
