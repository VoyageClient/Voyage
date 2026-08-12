/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import org.matrix.android.sdk.api.session.crypto.attachments.ElementToDecrypt
import org.matrix.android.sdk.api.session.file.FileService
import org.matrix.android.sdk.api.util.md5
import org.matrix.android.sdk.internal.crypto.attachments.MXEncryptedAttachments
import org.matrix.android.sdk.internal.di.Authenticated
import org.matrix.android.sdk.internal.di.SessionDownloadsDirectory
import org.matrix.android.sdk.internal.di.UnauthenticatedWithCertificateWithProgress
import org.matrix.android.sdk.internal.network.httpclient.addAuthenticationHeader
import org.matrix.android.sdk.internal.network.token.AccessTokenProvider
import org.matrix.android.sdk.internal.session.download.DownloadProgressInterceptor.Companion.DOWNLOAD_PROGRESS_INTERCEPTOR_HEADER
import org.matrix.android.sdk.internal.util.file.AtomicFileCreator
import org.matrix.android.sdk.internal.util.file.safeFileName
import org.matrix.android.sdk.internal.util.time.Clock
import org.matrix.android.sdk.internal.util.writeToFile
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject

internal class DefaultFileService @Inject constructor(
        private val context: Context,
        @SessionDownloadsDirectory
        private val sessionCacheDirectory: File,
        private val contentUrlResolver: ContentUrlResolver,
        @UnauthenticatedWithCertificateWithProgress
        private val okHttpClient: OkHttpClient,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val clock: Clock,
        @Authenticated private val accessTokenProvider: AccessTokenProvider,
        private val fileUploader: org.matrix.android.sdk.internal.session.content.FileUploader,
        private val imageCompressor: org.matrix.android.sdk.internal.session.content.ImageCompressor,
        private val pendingMediaUploadRegistry: org.matrix.android.sdk.internal.session.content.PendingMediaUploadRegistry,
        private val taskExecutor: org.matrix.android.sdk.internal.task.TaskExecutor,
) : FileService {

    override suspend fun uploadFile(uri: String, fileName: String?, mimeType: String?): String {
        return fileUploader.uploadFromUri(uri, fileName, mimeType).contentUri
    }

    override suspend fun compressImageForUpload(uri: String, mimeType: String?, maxDimension: Int): FileService.CompressedImageResult {
        return withContext(coroutineDispatchers.io) {
            val workingFile = File.createTempFile("compress", null, context.cacheDir).also { dest ->
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                } ?: throw IOException("Cannot open $uri")
            }
            val compressed = imageCompressor.compress(workingFile, maxDimension, maxDimension)
            // Never upload a re-encode that came out larger than the source; delete the temp file we don't keep
            // (the kept one is a cache file the caller deletes once the upload finishes).
            if (compressed.file != workingFile && compressed.file.length() >= workingFile.length()) {
                compressed.file.delete()
                FileService.CompressedImageResult(Uri.fromFile(workingFile).toString(), mimeType)
            } else {
                if (compressed.file != workingFile) workingFile.delete()
                FileService.CompressedImageResult(Uri.fromFile(compressed.file).toString(), compressed.mimeType ?: mimeType)
            }
        }
    }

    // Legacy folder, will be deleted
    private val legacyFolder = File(sessionCacheDirectory, "MF")

    // Folder to store downloaded files (not decrypted)
    private val downloadFolder = File(sessionCacheDirectory, "F")

    // Folder to store decrypted files
    private val decryptedFolder = File(downloadFolder, "D")

    init {
        // Clear the legacy downloaded files
        legacyFolder.deleteRecursively()
    }

    /**
     * Retain ongoing downloads to avoid re-downloading and already downloading file
     * map of mxCurl to callbacks.
     */
    private val ongoing = mutableMapOf<String, CompletableDeferred<File>>()

    /**
     * Download file in the cache folder, and eventually decrypt it.
     * TODO looks like files are copied 3 times
     */
    override suspend fun downloadFile(
            fileName: String,
            mimeType: String?,
            url: String?,
            elementToDecrypt: ElementToDecrypt?
    ): File {
        url ?: throw IllegalArgumentException("url is null")

        Timber.i("MEDIADBG download request url=$url")

        val deferred: CompletableDeferred<File>
        val isOwner: Boolean
        synchronized(ongoing) {
            val existing = ongoing[url]
            if (existing != null) {
                Timber.i("MEDIADBG download attach to ongoing url=$url (${ongoing.size} in flight)")
                deferred = existing
                isOwner = false
            } else {
                deferred = CompletableDeferred()
                ongoing[url] = deferred
                isOwner = true
            }
        }

        if (isOwner) {
            // Run in the session scope: all requests for this url share this one download, so a
            // cancelled requester (e.g. a recycled timeline view) must neither cancel the others
            // nor strand the ongoing entry, which would block even cache hits for this url forever.
            val job = taskExecutor.executorScope.launch(coroutineDispatchers.io) {
                val result = runCatching {
                    // Watchdog: whatever goes wrong below, the entry must eventually resolve.
                    withTimeout(DOWNLOAD_TIMEOUT_MS) {
                        performDownload(fileName, mimeType, url, elementToDecrypt)
                    }
                }
                Timber.i("MEDIADBG download settled url=$url ok=${result.isSuccess} err=${result.exceptionOrNull()}")
                // Remove before completing, so a caller arriving right after a failure starts a
                // fresh download instead of attaching to the stale failed deferred.
                synchronized(ongoing) { ongoing.remove(url) }
                deferred.completeWith(result)
            }
            job.invokeOnCompletion { cause ->
                synchronized(ongoing) { ongoing.remove(url) }
                if (deferred.isActive) {
                    deferred.completeExceptionally(cause ?: IllegalStateException("Download ended without a result"))
                }
            }
        }

        return deferred.await()
    }

    private suspend fun performDownload(
            fileName: String,
            mimeType: String?,
            url: String,
            elementToDecrypt: ElementToDecrypt?
    ): File {
        var atomicFileDownload: AtomicFileCreator? = null
        var atomicFileDecrypt: AtomicFileCreator? = null

        val result = runCatching {
            val cachedFiles = withContext(coroutineDispatchers.io) {
                if (!decryptedFolder.exists()) {
                    decryptedFolder.mkdirs()
                }

                // ensure we use unique file name by using URL (mapped to suitable file name)
                // Also we need to add extension for the FileProvider, if not it lot's of app that it's
                // shared with will not function well (even if mime type is passed in the intent)
                val cachedFiles = getFiles(url, fileName, mimeType, elementToDecrypt != null)

                // MSC2246: our own media whose bytes are still uploading. Asking the homeserver for it
                // would stall until the upload lands and then time out, so serve the local copy. Written
                // to the decrypted slot: the registry holds plaintext, which is what callers want here.
                pendingMediaUploadRegistry.getLocalFile(url)?.let { pending ->
                    val target = cachedFiles.getClearFile()
                    if (!target.exists()) {
                        target.parentFile?.mkdirs()
                        pending.copyTo(target, overwrite = true)
                    }
                    return@withContext cachedFiles
                }

                if (!cachedFiles.file.exists()) {
                    val resolvedMethod = contentUrlResolver.resolveForDownload(url, elementToDecrypt) ?: throw IllegalArgumentException("url is null")

                    val request = when (resolvedMethod) {
                        is ContentUrlResolver.ResolvedMethod.GET -> {
                            val requestBuilder = Request.Builder()
                                    .url(resolvedMethod.url)
                                    .header(DOWNLOAD_PROGRESS_INTERCEPTOR_HEADER, url)

                            if (contentUrlResolver.requiresAuthentication(resolvedMethod.url)) {
                                val accessToken = accessTokenProvider.getToken()
                                requestBuilder.addAuthenticationHeader(accessToken)
                            }
                            requestBuilder.build()
                        }

                        is ContentUrlResolver.ResolvedMethod.POST -> {
                            Request.Builder()
                                    .url(resolvedMethod.url)
                                    .header(DOWNLOAD_PROGRESS_INTERCEPTOR_HEADER, url)
                                    .post(RequestBody.create(MediaType.parse("application/json"), resolvedMethod.jsonBody))
                                    .build()
                        }
                    }

                    val response = try {
                        okHttpClient.newCall(request).execute()
                    } catch (failure: Throwable) {
                        throw if (failure is IOException) {
                            Failure.NetworkConnection(failure)
                        } else {
                            failure
                        }
                    }

                    if (!response.isSuccessful) {
                        // Deliberately not OtherServerError: that is formatted for homeserver API
                        // calls, so a media 404 would surface to the user as "homeserver not found"
                        // and anything else as a raw exception string. The status is kept in the
                        // message for logs only.
                        throw Failure.NetworkConnection(IOException("HTTP ${response.code()}"))
                    }

                    val source = response.body()?.source() ?: throw Failure.NetworkConnection(IOException())

                    Timber.v("Response size ${response.body()?.contentLength()} - Stream available: ${!source.exhausted()}")

                    // Write the file to cache (encrypted version if the file is encrypted)
                    // Write to a part file first, so if we abort before done, we don't have a broken cached file
                    val atomicFileCreator = AtomicFileCreator(cachedFiles.file).also { atomicFileDownload = it }
                    writeToFile(source.inputStream(), atomicFileCreator.partFile)
                    response.close()
                    atomicFileCreator.commit()
                    // Remember the server-advertised filename so getServerFileName can answer on cache hits.
                    parseContentDispositionFilename(response.header("Content-Disposition"))?.let { name ->
                        runCatching { File(cachedFiles.file.parentFile, SERVER_FILENAME_SIDECAR).writeText(name) }
                    }
                } else {
                    Timber.v("## FileService: cache hit for $url")
                }
                cachedFiles
            }

            // Decrypt if necessary. On the IO dispatcher: several callers invoke downloadFile from a
            // main-dispatcher scope, and streaming AES over a whole video on the main thread froze the UI.
            if (cachedFiles.decryptedFile != null) {
                withContext(coroutineDispatchers.io) {
                    if (!cachedFiles.decryptedFile.exists()) {
                        Timber.v("## FileService: decrypt file")
                        // Ensure the parent folder exists
                        cachedFiles.decryptedFile.parentFile?.mkdirs()
                        // Write to a part file first, so if we abort before done, we don't have a broken cached file
                        val atomicFileCreator = AtomicFileCreator(cachedFiles.decryptedFile).also { atomicFileDecrypt = it }
                        val decryptSuccess = cachedFiles.file.inputStream().use { inputStream ->
                            atomicFileCreator.partFile.outputStream().buffered().use { outputStream ->
                                MXEncryptedAttachments.decryptAttachment(
                                        inputStream,
                                        elementToDecrypt,
                                        outputStream,
                                        clock
                                )
                            }
                        }
                        atomicFileCreator.commit()
                        if (!decryptSuccess) {
                            throw IllegalStateException("Decryption error")
                        }
                    } else {
                        Timber.v("## FileService: cache hit for decrypted file")
                    }
                }
                cachedFiles.decryptedFile
            } else {
                // Clear file
                cachedFiles.file
            }
        }

        result.onFailure {
            atomicFileDownload?.cancel()
            atomicFileDecrypt?.cancel()
        }

        return result.getOrThrow()
    }

    override fun getServerFileName(url: String?): String? {
        url ?: return null
        return runCatching {
            File(downloadFolder, "${url.md5()}/$SERVER_FILENAME_SIDECAR")
                    .takeIf { it.exists() }
                    ?.readText()
                    ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    // RFC 6266: prefer the RFC 5987 `filename*=charset''percent-encoded` form, then the quoted and
    // bare `filename=` forms. The result is a display name, so strip any path components.
    private fun parseContentDispositionFilename(header: String?): String? {
        header ?: return null
        val raw = Regex("""filename\*\s*=\s*[Uu][Tt][Ff]-8''([^;]+)""").find(header)?.groupValues?.get(1)?.trim()
                ?.let { encoded -> runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrNull() }
                ?: Regex("""filename\s*=\s*"([^"]*)"""").find(header)?.groupValues?.get(1)
                ?: Regex("""filename\s*=\s*([^;]+)""").find(header)?.groupValues?.get(1)?.trim()
        return raw
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.filterNot { it.isISOControl() }
                ?.takeIf { it.isNotBlank() }
    }

    fun storeDataFor(
            mxcUrl: String,
            filename: String?,
            mimeType: String?,
            originalFile: File,
            encryptedFile: File?
    ) {
        val files = getFiles(mxcUrl, filename, mimeType, encryptedFile != null)
        if (encryptedFile != null) {
            // We switch the two files here, original file it the decrypted file
            files.decryptedFile?.let { originalFile.copyTo(it, overwrite = true) }
            encryptedFile.copyTo(files.file, overwrite = true)
        } else {
            // Just copy the original file
            originalFile.copyTo(files.file, overwrite = true)
        }
    }

    override fun getLocalFileFor(mxcUrl: String?, fileName: String?, mimeType: String?, isEncrypted: Boolean): File? {
        mxcUrl ?: return null
        pendingMediaUploadRegistry.getLocalFile(mxcUrl)?.let { return it }
        return getFiles(mxcUrl, fileName, mimeType, isEncrypted).getClearFile().takeIf { it.exists() }
    }

    override fun isUploadPending(mxcUrl: String?): Boolean {
        mxcUrl ?: return false
        return pendingMediaUploadRegistry.isPending(mxcUrl)
    }

    override fun isFileInCache(
            mxcUrl: String?,
            fileName: String,
            mimeType: String?,
            elementToDecrypt: ElementToDecrypt?
    ): Boolean {
        return fileState(mxcUrl, fileName, mimeType, elementToDecrypt) is FileService.FileState.InCache
    }

    internal data class CachedFiles(
            // This is the downloaded file. Can be clear or encrypted
            val file: File,
            // This is the decrypted file. Null if the original file is not encrypted
            val decryptedFile: File?
    ) {
        fun getClearFile(): File = decryptedFile ?: file
    }

    private fun getFiles(
            mxcUrl: String,
            fileName: String?,
            mimeType: String?,
            isEncrypted: Boolean
    ): CachedFiles {
        val hashFolder = mxcUrl.md5()
        val safeFileName = safeFileName(fileName, mimeType)
        return if (isEncrypted) {
            // Encrypted file
            CachedFiles(
                    File(downloadFolder, "$hashFolder/$ENCRYPTED_FILENAME"),
                    File(decryptedFolder, "$hashFolder/$safeFileName")
            )
        } else {
            // Clear file
            CachedFiles(
                    File(downloadFolder, "$hashFolder/$safeFileName"),
                    null
            )
        }
    }

    override fun fileState(
            mxcUrl: String?,
            fileName: String,
            mimeType: String?,
            elementToDecrypt: ElementToDecrypt?
    ): FileService.FileState {
        mxcUrl ?: return FileService.FileState.Unknown
        val files = getFiles(mxcUrl, fileName, mimeType, elementToDecrypt != null)
        if (files.file.exists()) {
            return FileService.FileState.InCache(
                    decryptedFileInCache = files.getClearFile().exists()
            )
        }
        val isDownloading = synchronized(ongoing) {
            ongoing[mxcUrl] != null
        }
        return if (isDownloading) FileService.FileState.Downloading else FileService.FileState.Unknown
    }

    /**
     * Use this URI and pass it to intent using flag Intent.FLAG_GRANT_READ_URI_PERMISSION
     * (if not other app won't be able to access it).
     */
    override fun getTemporarySharableURI(
            mxcUrl: String?,
            fileName: String,
            mimeType: String?,
            elementToDecrypt: ElementToDecrypt?
    ): String? {
        mxcUrl ?: return null
        // this string could be extracted no?
        val authority = "${context.packageName}.mx-sdk.fileprovider"
        val targetFile = getFiles(mxcUrl, fileName, mimeType, elementToDecrypt != null).getClearFile()
        if (!targetFile.exists()) return null
        return FileProvider.getUriForFile(context, authority, targetFile).toString()
    }

    override fun getCacheSize(): Long {
        return downloadFolder.walkTopDown()
                .onEnter {
                    Timber.v("Get size of ${it.absolutePath}")
                    true
                }
                .sumOf { it.length() }
    }

    override fun clearCache() {
        downloadFolder.deleteRecursively()
    }

    override fun clearDecryptedCache() {
        decryptedFolder.deleteRecursively()
    }

    companion object {
        // Generous, but a stuck download must eventually release its `ongoing` entry.
        private const val DOWNLOAD_TIMEOUT_MS = 30 * 60_000L

        private const val ENCRYPTED_FILENAME = "encrypted.bin"
        private const val SERVER_FILENAME_SIDECAR = ".server_filename"

        // The extension would be added from the mimetype
        const val DEFAULT_FILENAME = "file"
    }
}
