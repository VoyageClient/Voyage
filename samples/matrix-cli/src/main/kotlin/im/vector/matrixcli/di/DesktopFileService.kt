/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.matrixcli.di

import org.matrix.android.sdk.api.session.crypto.attachments.ElementToDecrypt
import org.matrix.android.sdk.api.session.file.FileService
import java.io.File
import javax.inject.Inject

// Desktop has no media cache yet; nothing on the login/sync/send path reaches these.
internal class DesktopFileService @Inject constructor() : FileService {

    override suspend fun downloadFile(fileName: String, mimeType: String?, url: String?, elementToDecrypt: ElementToDecrypt?): File =
            TODO("no media download on desktop")

    override fun fileState(mxcUrl: String?, fileName: String, mimeType: String?, elementToDecrypt: ElementToDecrypt?): FileService.FileState =
            FileService.FileState.Unknown

    override fun isFileInCache(mxcUrl: String?, fileName: String, mimeType: String?, elementToDecrypt: ElementToDecrypt?): Boolean = false

    override fun getTemporarySharableURI(mxcUrl: String?, fileName: String, mimeType: String?, elementToDecrypt: ElementToDecrypt?): String? = null

    override fun getLocalFileFor(mxcUrl: String?, fileName: String?, mimeType: String?, isEncrypted: Boolean): File? = null

    override fun isUploadPending(mxcUrl: String?): Boolean = false

    override fun getServerFileName(url: String?): String? = null

    override suspend fun uploadFile(uri: String, fileName: String?, mimeType: String?): String =
            TODO("no media upload on desktop")

    override suspend fun compressImageForUpload(uri: String, mimeType: String?, maxDimension: Int): FileService.CompressedImageResult =
            TODO("no image compression on desktop")

    override fun clearCache() = Unit

    override fun clearDecryptedCache() = Unit

    override fun getCacheSize(): Long = 0
}
