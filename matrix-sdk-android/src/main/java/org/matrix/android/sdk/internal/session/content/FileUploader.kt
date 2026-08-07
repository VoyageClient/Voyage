/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.content

import com.squareup.moshi.Moshi
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.MatrixUrls.toMxcParts
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilities
import org.matrix.android.sdk.api.session.homeserver.HomeServerCapabilitiesService
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.internal.di.Authenticated
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.ProgressRequestBody
import org.matrix.android.sdk.internal.network.awaitResponse
import org.matrix.android.sdk.internal.network.shouldFallBackToUnstableEndpoint
import org.matrix.android.sdk.internal.network.toFailure
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Servers cap how many content URIs may sit reserved-but-unused at once (Synapse: 5). Hitting that is
 * a reason to upload this one synchronously, not to fail the send.
 */
private fun Throwable.isPendingMediaLimitExceeded(): Boolean =
        this is Failure.ServerError && error.code == MatrixError.M_LIMIT_EXCEEDED

internal class FileUploader @Inject constructor(
        @Authenticated private val okHttpClient: OkHttpClient,
        private val globalErrorReceiver: GlobalErrorReceiver,
        private val homeServerCapabilitiesService: HomeServerCapabilitiesService,
        private val contentUriResolver: ContentUriResolver,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val imageExifTagRemover: ImageExifTagRemover,
        private val lightweightSettingsStorage: LightweightSettingsStorage,
        private val contentUrlResolver: ContentUrlResolver,
        moshi: Moshi
) {

    private val uploadUrl = contentUrlResolver.uploadUrl
    private val createUrl = contentUrlResolver.createUrl
    private val responseAdapter = moshi.adapter(ContentUploadResponse::class.java)
    private val createResponseAdapter = moshi.adapter(ContentCreateResponse::class.java)

    /**
     * Throws when the server is known to refuse a file this big, so the caller can fail before anything
     * is reserved or sent rather than after.
     */
    fun checkUploadSize(file: File) {
        val maxUploadFileSize = homeServerCapabilitiesService.getHomeServerCapabilities().maxUploadFileSize

        if (maxUploadFileSize != HomeServerCapabilities.MAX_UPLOAD_FILE_SIZE_UNKNOWN &&
                file.length() > maxUploadFileSize) {
            throw Failure.ServerError(
                    error = MatrixError(
                            code = MatrixError.M_TOO_LARGE,
                            message = "Cannot upload files larger than ${maxUploadFileSize / 1048576L}mb"
                    ),
                    httpCode = 413
            )
        }
    }

    /**
     * Reserve a content URI up front (MSC2246) so the event can be sent before its bytes exist.
     * Returns null when the server does not offer `media/v1/create`, or is momentarily unwilling to
     * reserve another one — both mean "just upload it the old way".
     */
    suspend fun createMedia(): ContentCreateResponse? {
        val request = Request.Builder()
                .url(createUrl)
                .post(RequestBody.create(null, ByteArray(0)))
                .build()

        return withContext(coroutineDispatchers.io) {
            try {
                okHttpClient.newCall(request).awaitResponse().use { response ->
                    if (!response.isSuccessful) throw response.toFailure(globalErrorReceiver)
                    response.body()?.source()?.let { createResponseAdapter.fromJson(it) } ?: throw IOException()
                }
            } catch (failure: Throwable) {
                if (failure.shouldFallBackToUnstableEndpoint() || failure.isPendingMediaLimitExceeded()) {
                    Timber.d(failure, "## Media create unavailable, falling back to a synchronous upload")
                    null
                } else {
                    throw failure
                }
            }
        }
    }

    /**
     * Upload the bytes of a content URI previously reserved by [createMedia].
     */
    suspend fun uploadReservedFile(
            contentUri: String,
            file: File,
            filename: String?,
            mimeType: String?,
            progressListener: ProgressRequestBody.Listener? = null
    ) {
        val (serverName, mediaId) = contentUri.toMxcParts()
                ?: throw IllegalArgumentException("Not a valid mxc uri: $contentUri")

        val uploadBody = object : RequestBody() {
            override fun contentLength() = file.length()

            override fun contentType(): MediaType? {
                return mimeType?.let { MediaType.parse(it) }
            }

            override fun writeTo(sink: BufferedSink) {
                file.source().use { sink.writeAll(it) }
            }
        }

        val httpUrl = HttpUrl.parse(contentUrlResolver.uploadUrlForReserved(serverName, mediaId))
                ?.newBuilder()
                ?.apply { if (filename != null) addQueryParameter("filename", filename) }
                ?.build()
                ?: throw RuntimeException()

        val requestBody = if (progressListener != null) ProgressRequestBody(uploadBody, progressListener) else uploadBody

        val request = Request.Builder()
                .url(httpUrl)
                .put(requestBody)
                .build()

        withContext(coroutineDispatchers.io) {
            okHttpClient.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) {
                    throw response.toFailure(globalErrorReceiver)
                }
            }
        }
    }

    suspend fun uploadFile(
            file: File,
            filename: String?,
            mimeType: String?,
            progressListener: ProgressRequestBody.Listener? = null
    ): ContentUploadResponse {
        // Known limitation and file too big for the server, save the pain to upload it
        checkUploadSize(file)

        val uploadBody = object : RequestBody() {
            override fun contentLength() = file.length()

            override fun contentType(): MediaType? {
                return mimeType?.let { MediaType.parse(it) }
            }

            override fun writeTo(sink: BufferedSink) {
                file.source().use { sink.writeAll(it) }
            }
        }

        return upload(uploadBody, filename, progressListener)
    }

    suspend fun uploadByteArray(
            byteArray: ByteArray,
            filename: String?,
            mimeType: String?,
            progressListener: ProgressRequestBody.Listener? = null
    ): ContentUploadResponse {
        val uploadBody = RequestBody.create(mimeType?.let { MediaType.parse(it) }, byteArray)
        return upload(uploadBody, filename, progressListener)
    }

    suspend fun uploadFromUri(
            uri: String,
            filename: String?,
            mimeType: String?,
            progressListener: ProgressRequestBody.Listener? = null
    ): ContentUploadResponse {
        val workingFile = contentUriResolver.copyToTempFile(uri)
        // Avatars, banners and image-pack stickers upload the picked bytes directly (they don't go
        // through the timeline-media worker), so scrub their EXIF/location here too when enabled.
        // Lossless only — non-images and formats that can't be scrubbed in place are left untouched.
        val fileToUpload = if (lightweightSettingsStorage.shouldStripMediaMetadata()) {
            imageExifTagRemover.stripImageMetadata(workingFile) ?: workingFile
        } else {
            workingFile
        }
        return uploadFile(fileToUpload, filename, mimeType, progressListener).also {
            tryOrNull { workingFile.delete() }
            if (fileToUpload !== workingFile) tryOrNull { fileToUpload.delete() }
        }
    }

    private suspend fun upload(
            uploadBody: RequestBody,
            filename: String?,
            progressListener: ProgressRequestBody.Listener?
    ): ContentUploadResponse {
        val urlBuilder = HttpUrl.parse(uploadUrl)?.newBuilder() ?: throw RuntimeException()

        val httpUrl = urlBuilder
                .apply {
                    if (filename != null) {
                        addQueryParameter("filename", filename)
                    }
                }
                .build()

        val requestBody = if (progressListener != null) ProgressRequestBody(uploadBody, progressListener) else uploadBody

        val request = Request.Builder()
                .url(httpUrl)
                .post(requestBody)
                .build()

        return withContext(coroutineDispatchers.io) {
            okHttpClient.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) {
                    throw response.toFailure(globalErrorReceiver)
                } else {
                    response.body()?.source()?.let {
                        responseAdapter.fromJson(it)
                    }
                            ?: throw IOException()
                }
            }
        }
    }
}
