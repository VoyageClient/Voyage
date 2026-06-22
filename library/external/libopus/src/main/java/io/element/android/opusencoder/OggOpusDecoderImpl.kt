/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package io.element.android.opusencoder

import android.util.Log

internal class OggOpusDecoderImpl : OggOpusDecoder {

    companion object {

        private const val TAG = "OggOpusDecoder"

        init {
            try {
                System.loadLibrary("opuscodec")
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't load opus library: $e")
            }
        }
    }

    override fun decodeToWav(inputPath: String, outputPath: String): Int {
        return nativeDecodeToWav(inputPath, outputPath)
    }

    private external fun nativeDecodeToWav(inputPath: String, outputPath: String): Int
}
