/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package io.element.android.opusencoder

interface OggOpusDecoder {

    /**
     * Decodes the Ogg/Opus file at [inputPath] into a 16-bit PCM WAV file at [outputPath].
     * Returns 0 on success, or a negative libopusfile error code on failure.
     */
    fun decodeToWav(inputPath: String, outputPath: String): Int

    companion object {
        fun create(): OggOpusDecoder = OggOpusDecoderImpl()
    }
}
