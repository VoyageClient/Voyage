/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

import okhttp3.Response
import org.matrix.android.sdk.api.util.JsonDict
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** A phone is not a media repository: Synapse spiders up to 10 MB, we stop far earlier. */
internal const val MAX_PREVIEW_IMAGE_SIZE = 2 * 1024 * 1024

/**
 * The preview of a page, whether this device or the homeserver read it, and the thumbnail to upload
 * with it.
 */
internal data class FetchedPreview(
        val fields: JsonDict,
        val image: FetchedImage?
)

internal class FetchedImage(
        val bytes: ByteArray,
        val mimeType: String?
)

/**
 * Reads a thumbnail, refusing anything which is not an image or is too big to send along with a
 * message. Half an image is of no use, so an overlong one is dropped rather than truncated.
 */
internal fun Response.readThumbnail(): FetchedImage? {
    val mimeType = header("Content-Type")?.substringBefore(';')?.trim()
    if (mimeType?.startsWith("image/") != true) return null
    val body = body() ?: return null
    if (body.contentLength() > MAX_PREVIEW_IMAGE_SIZE) return null
    val bytes = body.byteStream().readAtMost(MAX_PREVIEW_IMAGE_SIZE)
    return if (bytes.size > MAX_PREVIEW_IMAGE_SIZE || bytes.isEmpty()) null else FetchedImage(bytes, mimeType)
}

/**
 * Reads the stream until it holds more than [maxBytes], so that a caller can tell a complete read from
 * one which ran over. The announced length cannot be trusted for this: servers may not send one.
 */
internal fun InputStream.readAtMost(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    use { input ->
        while (output.size() <= maxBytes) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
        }
    }
    return output.toByteArray()
}
