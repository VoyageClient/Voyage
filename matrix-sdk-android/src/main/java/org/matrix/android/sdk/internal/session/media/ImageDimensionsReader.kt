/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.media

/**
 * Reads the pixel size of an encoded image without decoding it whole. Only the header is needed, so
 * android answers from BitmapFactory's bounds pass and a JVM from ImageIO's readers.
 */
internal interface ImageDimensionsReader {

    /** @return width to height, or null if [bytes] isn't a decodable image. */
    fun read(bytes: ByteArray): Pair<Int, Int>?
}
