/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject

internal class AndroidContentUriResolver @Inject constructor(
        private val context: Context,
        private val temporaryFileCreator: TemporaryFileCreator,
) : ContentUriResolver {

    override suspend fun copyToTempFile(uriString: String): File = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(Uri.parse(uriString)) ?: throw FileNotFoundException()
        val workingFile = temporaryFileCreator.create()
        workingFile.outputStream().use {
            inputStream.copyTo(it)
        }
        inputStream.close()
        workingFile
    }
}
