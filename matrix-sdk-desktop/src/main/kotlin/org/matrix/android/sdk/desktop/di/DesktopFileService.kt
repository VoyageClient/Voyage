/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.desktop.di

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import org.matrix.android.sdk.api.session.crypto.attachments.ElementToDecrypt
import org.matrix.android.sdk.api.session.file.FileService
import org.matrix.android.sdk.internal.crypto.attachments.MXEncryptedAttachments
import org.matrix.android.sdk.internal.di.Authenticated
import org.matrix.android.sdk.internal.di.SessionDownloadsDirectory
import org.matrix.android.sdk.internal.di.UnauthenticatedWithCertificateWithProgress
import org.matrix.android.sdk.internal.network.httpclient.addAuthenticationHeader
import org.matrix.android.sdk.internal.network.token.AccessTokenProvider
import org.matrix.android.sdk.internal.session.content.FileUploader
import org.matrix.android.sdk.internal.session.content.UploadedMediaCache
import org.matrix.android.sdk.internal.util.time.Clock
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import javax.inject.Inject

/**
 * Media on a plain JVM: downloads land in the session's downloads directory (mirroring android's
 * encrypted/decrypted split), uploads go through the shared [FileUploader], and image compression
 * uses ImageIO. No progress trackers — nothing on desktop draws a progress bar yet.
 */
internal class DesktopFileService @Inject constructor(
        @SessionDownloadsDirectory private val downloadsDirectory: File,
        @UnauthenticatedWithCertificateWithProgress private val okHttpClient: OkHttpClient,
        private val contentUrlResolver: ContentUrlResolver,
        @Authenticated private val accessTokenProvider: AccessTokenProvider,
        private val fileUploader: FileUploader,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val clock: Clock,
) : FileService, UploadedMediaCache {

    private val encryptedFolder = File(downloadsDirectory, "F")
    private val decryptedFolder = File(encryptedFolder, "D")

    // One download per url: several timeline items can ask for the same media at once.
    private val downloadLocks = HashMap<String, Mutex>()

    override suspend fun downloadFile(fileName: String, mimeType: String?, url: String?, elementToDecrypt: ElementToDecrypt?): File {
        url ?: throw IllegalArgumentException("url is null")
        val lock = synchronized(downloadLocks) { downloadLocks.getOrPut(url) { Mutex() } }
        return lock.withLock {
            withContext(coroutineDispatchers.io) {
                val target = clearFile(url, fileName, mimeType, elementToDecrypt != null)
                if (target.exists()) return@withContext target
                val downloaded = downloadedFile(url, fileName, mimeType, elementToDecrypt != null)
                if (!downloaded.exists()) {
                    downloaded.parentFile?.mkdirs()
                    fetch(url, elementToDecrypt, downloaded)
                }
                if (elementToDecrypt != null) {
                    target.parentFile?.mkdirs()
                    downloaded.inputStream().use { input ->
                        target.outputStream().use { output ->
                            MXEncryptedAttachments.decryptAttachment(input, elementToDecrypt, output, clock)
                        }
                    }
                }
                target
            }
        }
    }

    private fun fetch(url: String, elementToDecrypt: ElementToDecrypt?, target: File) {
        val resolved = contentUrlResolver.resolveForDownload(url, elementToDecrypt)
                ?: throw IllegalArgumentException("$url cannot be resolved")
        val request = when (resolved) {
            is ContentUrlResolver.ResolvedMethod.GET -> Request.Builder()
                    .url(resolved.url)
                    .apply {
                        if (contentUrlResolver.requiresAuthentication(resolved.url)) {
                            addAuthenticationHeader(accessTokenProvider.getToken())
                        }
                    }
                    .build()
            is ContentUrlResolver.ResolvedMethod.POST -> Request.Builder()
                    .url(resolved.url)
                    .post(RequestBody.create(MediaType.parse("application/json"), resolved.jsonBody))
                    .build()
        }
        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (failure: IOException) {
            throw Failure.NetworkConnection(failure)
        }
        response.use {
            if (!it.isSuccessful) throw Failure.NetworkConnection(IOException("HTTP ${it.code()}"))
            val source = it.body()?.source() ?: throw Failure.NetworkConnection(IOException("empty body"))
            // Write through a part file so an interrupted download can't be served as a cache hit.
            val partFile = File(target.parentFile, "${target.name}.part")
            partFile.outputStream().use { output -> source.inputStream().copyTo(output) }
            if (!partFile.renameTo(target)) {
                partFile.copyTo(target, overwrite = true)
                partFile.delete()
            }
        }
    }

    override fun fileState(mxcUrl: String?, fileName: String, mimeType: String?, elementToDecrypt: ElementToDecrypt?): FileService.FileState {
        mxcUrl ?: return FileService.FileState.Unknown
        // isFileInCache tests the usable file, so a hit here always has plaintext bytes on disk.
        return if (isFileInCache(mxcUrl, fileName, mimeType, elementToDecrypt)) {
            FileService.FileState.InCache(decryptedFileInCache = true)
        } else {
            FileService.FileState.Unknown
        }
    }

    override fun isFileInCache(mxcUrl: String?, fileName: String, mimeType: String?, elementToDecrypt: ElementToDecrypt?): Boolean {
        mxcUrl ?: return false
        return clearFile(mxcUrl, fileName, mimeType, elementToDecrypt != null).exists()
    }

    override fun getTemporarySharableURI(mxcUrl: String?, fileName: String, mimeType: String?, elementToDecrypt: ElementToDecrypt?): String? {
        return getLocalFileFor(mxcUrl, fileName, mimeType, elementToDecrypt != null)?.toLocalUri()
    }

    override fun getLocalFileFor(mxcUrl: String?, fileName: String?, mimeType: String?, isEncrypted: Boolean): File? {
        mxcUrl ?: return null
        return clearFile(mxcUrl, fileName.orEmpty(), mimeType, isEncrypted).takeIf { it.exists() }
    }

    override fun isUploadPending(mxcUrl: String?): Boolean = false

    override fun getServerFileName(url: String?): String? = null

    override suspend fun uploadFile(uri: String, fileName: String?, mimeType: String?): String {
        return fileUploader.uploadFromUri(uri, fileName, mimeType).contentUri
    }

    override suspend fun compressImageForUpload(uri: String, mimeType: String?, maxDimension: Int): FileService.CompressedImageResult {
        return withContext(coroutineDispatchers.io) {
            val source = uri.toLocalFile()
            val image = ImageIO.read(source) ?: return@withContext FileService.CompressedImageResult(uri, mimeType)
            val scale = maxDimension.toDouble() / maxOf(image.width, image.height)
            if (scale >= 1.0) return@withContext FileService.CompressedImageResult(uri, mimeType)
            val target = File(downloadsDirectory, "compressed-${clock.epochMillis()}-${source.nameWithoutExtension}.jpg")
            val scaled = java.awt.image.BufferedImage(
                    (image.width * scale).toInt().coerceAtLeast(1),
                    (image.height * scale).toInt().coerceAtLeast(1),
                    java.awt.image.BufferedImage.TYPE_INT_RGB,
            )
            scaled.createGraphics().run {
                drawImage(image.getScaledInstance(scaled.width, scaled.height, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
                dispose()
            }
            ImageIO.write(scaled, "jpg", target)
            // A re-encode that came out bigger than the source is not worth uploading.
            if (target.length() >= source.length()) {
                target.delete()
                FileService.CompressedImageResult(uri, mimeType)
            } else {
                FileService.CompressedImageResult(target.toLocalUri(), "image/jpeg")
            }
        }
    }

    override fun clearCache() {
        encryptedFolder.deleteRecursively()
    }

    override fun clearDecryptedCache() {
        decryptedFolder.deleteRecursively()
    }

    override fun getCacheSize(): Long = encryptedFolder.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    override fun storeDataFor(mxcUrl: String, filename: String?, mimeType: String?, originalFile: File, encryptedFile: File?) {
        val name = filename ?: "file"
        if (encryptedFile != null) {
            clearFile(mxcUrl, name, mimeType, true).also { it.parentFile?.mkdirs() }.let { originalFile.copyTo(it, overwrite = true) }
            downloadedFile(mxcUrl, name, mimeType, true).also { it.parentFile?.mkdirs() }.let { encryptedFile.copyTo(it, overwrite = true) }
        } else {
            downloadedFile(mxcUrl, name, mimeType, false).also { it.parentFile?.mkdirs() }.let { originalFile.copyTo(it, overwrite = true) }
        }
    }

    private fun downloadedFile(mxcUrl: String, fileName: String, mimeType: String?, isEncrypted: Boolean) =
            File(encryptedFolder, cacheName(mxcUrl, fileName, mimeType, isEncrypted))

    /** Where the usable bytes end up: the download itself, or its decrypted copy. */
    private fun clearFile(mxcUrl: String, fileName: String, mimeType: String?, isEncrypted: Boolean) =
            if (isEncrypted) {
                File(decryptedFolder, cacheName(mxcUrl, fileName, mimeType, false))
            } else {
                downloadedFile(mxcUrl, fileName, mimeType, false)
            }

    private fun cacheName(mxcUrl: String, fileName: String, mimeType: String?, isEncrypted: Boolean): String {
        val extension = fileName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
                ?: mimeType?.substringAfterLast('/')
        return buildString {
            append(mxcUrl.hashCode().toUInt().toString(16))
            append('-')
            append(fileName.replace(UNSAFE_NAME, "_").take(MAX_NAME_LENGTH).ifEmpty { "file" })
            if (isEncrypted) append(".encrypted")
            if (extension != null && !endsWith(".$extension")) append(".$extension")
        }
    }

    companion object {
        private val UNSAFE_NAME = Regex("""[^A-Za-z0-9._-]""")
        private const val MAX_NAME_LENGTH = 64
    }
}
