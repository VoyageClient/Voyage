/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.animatedimage

import java.io.File

/** Reads any of the animated image formats into a plain sequence of complete frames. */
object AnimatedImageReader {

    /** @return null when the file is not an animation, or cannot be decoded. */
    fun readFrames(file: File, format: AnimatedImageFormat? = null): List<AnimatedFrame>? {
        return when (format ?: AnimatedImageFormat.detect(file)) {
            AnimatedImageFormat.GIF -> GifFrameReader.readFrames(file)
            AnimatedImageFormat.APNG -> ApngFrameReader.readFrames(file)
            AnimatedImageFormat.WEBP -> AnimatedWebpReader.readFrames(file)
            null -> null
        }
    }
}
