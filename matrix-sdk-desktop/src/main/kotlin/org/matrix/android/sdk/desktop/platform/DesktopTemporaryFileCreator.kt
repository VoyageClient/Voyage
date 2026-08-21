/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.desktop.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.internal.di.CacheDirectory
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import java.io.File
import java.util.UUID
import javax.inject.Inject

internal class DesktopTemporaryFileCreator @Inject constructor(
        @CacheDirectory private val cacheDir: File,
) : TemporaryFileCreator {
    override suspend fun create(): File = withContext(Dispatchers.IO) {
        val dir = File(cacheDir, "tmp").also { it.mkdirs() }
        File.createTempFile(UUID.randomUUID().toString(), null, dir)
    }
}
