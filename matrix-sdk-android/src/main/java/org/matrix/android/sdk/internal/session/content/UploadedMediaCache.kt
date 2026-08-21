/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import java.io.File

/**
 * Lets the upload pipeline seed the file cache with the bytes it just sent, so the sender's own
 * timeline serves them without a download. Implemented by each platform's FileService.
 */
internal interface UploadedMediaCache {
    fun storeDataFor(mxcUrl: String, filename: String?, mimeType: String?, originalFile: File, encryptedFile: File?)
}
