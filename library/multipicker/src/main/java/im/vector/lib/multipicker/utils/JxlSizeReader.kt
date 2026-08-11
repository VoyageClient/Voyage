/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.lib.multipicker.utils

import android.content.Context
import android.net.Uri
import com.awxkee.jxlcoder.JxlCoder

/**
 * libjxl loads its .so from a static initialiser, so this must only be touched above API 21 — see
 * the caller in [ImageUtils].
 */
internal object JxlSizeReader {

    fun read(context: Context, uri: Uri): ImageSize? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val size = JxlCoder.getSize(bytes) ?: return null
        return ImageSize(size.width, size.height)
    }
}
