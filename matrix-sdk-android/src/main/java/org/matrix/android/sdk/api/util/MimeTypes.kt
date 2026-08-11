/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.api.util

import org.matrix.android.sdk.api.extensions.orFalse

// The Android SDK does not provide constant for mime type, add some of them here
object MimeTypes {
    const val Any: String = "*/*"
    const val OctetStream = "application/octet-stream"
    const val Apk = "application/vnd.android.package-archive"

    const val Images = "image/*"

    const val Png = "image/png"
    const val Apng = "image/apng"
    const val BadJpg = "image/jpg"
    const val Jpeg = "image/jpeg"
    const val Gif = "image/gif"
    const val Webp = "image/webp"
    const val Jxl = "image/jxl"
    const val Svg = "image/svg+xml"

    const val Mp4 = "video/mp4"

    const val Ogg = "audio/ogg"

    const val PlainText = "text/plain"
    const val Html = "text/html"

    fun String?.normalizeMimeType() = if (this == BadJpg) Jpeg else this

    /**
     * Extension for a type we can produce by converting an attachment. Null for anything else, so
     * callers leave a name they can't improve on alone rather than inventing an extension.
     */
    fun extensionForMimeType(mimeType: String?): String? = when (mimeType?.lowercase()) {
        Jpeg, BadJpg -> "jpg"
        Png -> "png"
        Gif -> "gif"
        Webp -> "webp"
        Jxl -> "jxl"
        Mp4 -> "mp4"
        "video/webm" -> "webm"
        "audio/ogg" -> "ogg"
        "audio/mpeg" -> "mp3"
        "audio/mp4", "audio/aac" -> "m4a"
        else -> null
    }

    /** Rewrites [name]'s extension to match [mimeType]; unchanged when the type isn't one we map. */
    fun renameForMimeType(name: String?, mimeType: String?): String? {
        if (name == null) return null
        val extension = extensionForMimeType(mimeType) ?: return name
        if (name.substringAfterLast('.', "").lowercase() == extension) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        return "$base.$extension"
    }

    fun String?.isMimeTypeImage() = this?.startsWith("image/").orFalse()
    fun String?.isMimeTypeVideo() = this?.startsWith("video/").orFalse()
    fun String?.isMimeTypeAudio() = this?.startsWith("audio/").orFalse()
    fun String?.isMimeTypeApplication() = this?.startsWith("application/").orFalse()
    fun String?.isMimeTypeFile() = this?.startsWith("file/").orFalse()
    fun String?.isMimeTypeText() = this?.startsWith("text/").orFalse()
    fun String?.isMimeTypeAny() = this?.startsWith("*/").orFalse()
}
