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

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.work.WorkerParameters
import com.squareup.moshi.JsonClass
import com.vanniktech.blurhash.BlurHash
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.listeners.ProgressListener
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.session.content.queryUriAndroid
import org.matrix.android.sdk.api.session.crypto.model.EncryptedFileInfo
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.AudioWaveformInfo
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageFileContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.internal.SessionManager
import org.matrix.android.sdk.internal.crypto.attachments.MXEncryptedAttachments
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.network.ProgressRequestBody
import org.matrix.android.sdk.internal.session.DefaultFileService
import org.matrix.android.sdk.internal.session.SessionComponent
import org.matrix.android.sdk.internal.session.room.send.CancelSendTracker
import org.matrix.android.sdk.internal.session.room.send.LocalEchoIdentifiers
import org.matrix.android.sdk.internal.session.room.send.LocalEchoRepository
import org.matrix.android.sdk.internal.session.room.send.MultipleEventSendingDispatcherWorkerParams
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import org.matrix.android.sdk.internal.util.time.Clock
import org.matrix.android.sdk.internal.util.toMatrixErrorStr
import org.matrix.android.sdk.internal.worker.SessionSafeCoroutineWorker
import org.matrix.android.sdk.internal.worker.SessionWorkerParams
import org.matrix.android.sdk.internal.worker.WorkerParamsFactory
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject

private data class NewAttachmentAttributes(
        val newWidth: Int? = null,
        val newHeight: Int? = null,
        val newFileSize: Long,
        val newMimeType: String? = null,
)

/**
 * Possible previous worker: None.
 * Possible next worker    : Always [MultipleEventSendingDispatcherWorker].
 */
private typealias Params = UploadContentWorkerParams

internal class UploadContentWorker(val context: Context, params: WorkerParameters, sessionManager: SessionManager) :
        SessionSafeCoroutineWorker<UploadContentWorkerParams>(context, params, sessionManager, Params::class.java) {

    @Inject lateinit var fileUploader: FileUploader
    @Inject lateinit var contentUploadStateTracker: DefaultContentUploadStateTracker
    @Inject lateinit var fileService: DefaultFileService
    @Inject lateinit var cancelSendTracker: CancelSendTracker
    @Inject lateinit var imageCompressor: ImageCompressor
    @Inject lateinit var imageExitTagRemover: ImageExifTagRemover
    @Inject lateinit var videoMetadataStripper: VideoMetadataStripper
    @Inject lateinit var videoCompressor: VideoCompressor
    @Inject lateinit var lightweightSettingsStorage: LightweightSettingsStorage
    @Inject lateinit var thumbnailExtractor: ThumbnailExtractor
    @Inject lateinit var localEchoRepository: LocalEchoRepository
    @Inject lateinit var temporaryFileCreator: TemporaryFileCreator
    @Inject lateinit var clock: Clock

    override fun injectWith(injector: SessionComponent) {
        injector.inject(this)
    }

    override suspend fun doSafeWork(params: Params): Result {
        Timber.v("Starting upload media work with params $params")
        // Just defensive code to ensure that we never have an uncaught exception that could break the queue
        return try {
            internalDoWork(params)
        } catch (failure: Throwable) {
            Timber.e(failure)
            handleFailure(params, failure)
        }
    }

    override fun buildErrorParams(params: Params, message: String): Params {
        return params.copy(lastFailureMessage = params.lastFailureMessage ?: message)
    }

    private fun isCancelled(params: Params): Boolean =
            isStopped || params.localEchoIds.all { cancelSendTracker.isCancelRequestedFor(it.eventId, it.roomId) }

    private suspend fun internalDoWork(params: Params): Result {
        if (isCancelled(params)) {
            Timber.e("## Send: Work cancelled by user")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.revokeUriPermission(context.packageName, params.attachment.queryUriAndroid, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                context.revokeUriPermission(params.attachment.queryUriAndroid, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return Result.failure()
        }

        val attachment = params.attachment
        val filesToDelete = hashSetOf<File>()

        return try {
            // Materialize the source to a local temp file. Deferred until first access so the
            // video+compress happy path (which feeds the source URI straight into Media3) never
            // pays the cost of copying a multi-hundred-MB file.
            var cachedWorkingFile: File? = null
            suspend fun workingFile(): File {
                cachedWorkingFile?.let { return it }
                val f = temporaryFileCreator.create().also { filesToDelete.add(it) }
                val input = context.contentResolver.openInputStream(attachment.queryUriAndroid)
                        ?: throw IOException("Cannot openInputStream for file: ${attachment.queryUri}")
                input.use { inStream -> f.outputStream().use { inStream.copyTo(it) } }
                cachedWorkingFile = f
                return f
            }

            val progressListener = object : ProgressRequestBody.Listener {
                override fun onProgress(current: Long, total: Long) {
                    notifyTracker(params) {
                        if (isStopped) {
                            contentUploadStateTracker.setFailure(it, Throwable("Cancelled"))
                        } else {
                            contentUploadStateTracker.setProgress(it, current, total)
                        }
                    }
                }
            }

            var uploadedFileEncryptedFileInfo: EncryptedFileInfo? = null

            try {
                val fileToUpload: File
                var transcodedVideoFile: File? = null
                var audioWaveform: List<Int>? = null
                var newAttachmentAttributes = NewAttachmentAttributes(
                        params.attachment.width?.toInt(),
                        params.attachment.height?.toInt(),
                        params.attachment.size
                )

                val stripMetadata = lightweightSettingsStorage.shouldStripMediaMetadata()

                // Anything the sender chose is honoured even at "original size", which only means
                // "don't apply the automatic downscale".
                if (attachment.type == ContentAttachmentData.Type.IMAGE &&
                        (params.compressBeforeSending || attachment.hasCustomCompression)) {
                    notifyTracker(params) { contentUploadStateTracker.setCompressingImage(it) }

                    val compressed = imageCompressor.compress(
                            workingFile(),
                            attachment.compressionWidth ?: MAX_IMAGE_SIZE,
                            attachment.compressionHeight ?: MAX_IMAGE_SIZE,
                            imageQualityFor(attachment.compressionQuality ?: STANDARD_QUALITY),
                            exactSize = attachment.compressionWidth != null,
                    )
                    fileToUpload = compressed.file.also { filesToDelete.add(it) }
                    newAttachmentAttributes = measureImageAttributes(fileToUpload, compressed.mimeType)
                } else if (attachment.type == ContentAttachmentData.Type.VIDEO &&
                        (params.compressBeforeSending || attachment.hasCustomCompression)) {
                    val outcome = compressVideo(params, newAttachmentAttributes, filesToDelete, ::workingFile, stripMetadata)
                    fileToUpload = outcome.fileToUpload
                    newAttachmentAttributes = outcome.attributes
                    transcodedVideoFile = outcome.transcodedFile
                } else if (attachment.type == ContentAttachmentData.Type.IMAGE) {
                    // Original-size image: strip metadata in place when possible, otherwise re-encode.
                    val working = workingFile()
                    val stripped = if (stripMetadata) imageExitTagRemover.stripImageMetadata(working) else working
                    if (stripped != null) {
                        fileToUpload = stripped.also { if (it !== working) filesToDelete.add(it) }
                        newAttachmentAttributes = newAttachmentAttributes.copy(newFileSize = fileToUpload.length())
                    } else {
                        // Format can't be scrubbed in place (e.g. HEIC) — re-encode so nothing leaks.
                        notifyTracker(params) { contentUploadStateTracker.setCompressingImage(it) }
                        val reEncoded = imageCompressor.reEncodeStrippingMetadata(working)
                        fileToUpload = reEncoded.file.also { if (it !== working) filesToDelete.add(it) }
                        newAttachmentAttributes = if (reEncoded.mimeType != null) {
                            measureImageAttributes(fileToUpload, reEncoded.mimeType)
                        } else {
                            newAttachmentAttributes.copy(newFileSize = fileToUpload.length())
                        }
                    }
                } else {
                    val working = workingFile()
                    // A JPEG/PNG/WebP shared through the file/document picker lands here rather than the
                    // IMAGE branch; scrub it too so "send as file" doesn't leak EXIF. Videos sent at
                    // original size are re-muxed to drop their location/metadata atoms.
                    fileToUpload = when {
                        !stripMetadata -> working
                        attachment.type == ContentAttachmentData.Type.FILE ->
                            (imageExitTagRemover.stripImageMetadata(working) ?: working).also { scrubbed ->
                                if (scrubbed !== working) {
                                    filesToDelete.add(scrubbed)
                                    newAttachmentAttributes = newAttachmentAttributes.copy(newFileSize = scrubbed.length())
                                }
                            }
                        attachment.type == ContentAttachmentData.Type.VIDEO ->
                            (videoMetadataStripper.strip(working) ?: working).also { stripped ->
                                if (stripped !== working) {
                                    filesToDelete.add(stripped)
                                    newAttachmentAttributes = newAttachmentAttributes.copy(newFileSize = stripped.length())
                                }
                            }
                        else -> working
                    }
                    if (attachment.type == ContentAttachmentData.Type.VIDEO) {
                        // The echo's videoInfo was measured against the decoded frame (display
                        // orientation); the picker metadata can be orientation-blind, so don't
                        // overwrite the echo's dims when sending the original file.
                        newAttachmentAttributes = newAttachmentAttributes.copy(newWidth = null, newHeight = null)
                    }
                    val storedWaveform = attachment.waveform
                    val needsWaveform = attachment.type == ContentAttachmentData.Type.VOICE_MESSAGE &&
                            (storedWaveform.isNullOrEmpty() || storedWaveform.all { it == 0 })
                    if (needsWaveform) {
                        notifyTracker(params) { contentUploadStateTracker.setProcessingAudio(it) }
                        audioWaveform = AudioWaveformExtractor.extract(fileToUpload).takeIf { it.isNotEmpty() }
                    }
                    // Fix: OpenableColumns.SIZE may return -1 or 0
                    if (params.attachment.size <= 0) {
                        newAttachmentAttributes = newAttachmentAttributes.copy(newFileSize = fileToUpload.length())
                    }
                }

                // Compression can take a long time; re-check cancellation here so a cancel that
                // arrived mid-compress short-circuits the chain (failure stops the dispatcher
                // from then trying to send the half-baked event with a local file:// URL).
                if (isCancelled(params)) {
                    notifyTracker(params) { contentUploadStateTracker.setFailure(it, Throwable("Cancelled")) }
                    return Result.failure()
                }

                val encryptedFile: File?
                val contentUploadResponse = if (params.isEncrypted) {
                    Timber.v("## Encrypt file")
                    encryptedFile = temporaryFileCreator.create()
                            .also { filesToDelete.add(it) }

                    uploadedFileEncryptedFileInfo =
                            MXEncryptedAttachments.encrypt(fileToUpload.inputStream(), encryptedFile, clock) { read, total ->
                                notifyTracker(params) {
                                    contentUploadStateTracker.setEncrypting(it, read.toLong(), total.toLong())
                                }
                            }
                    Timber.v("## Uploading file")
                    fileUploader.uploadFile(
                            file = encryptedFile,
                            filename = null,
                            mimeType = MimeTypes.OctetStream,
                            progressListener = progressListener
                    )
                } else {
                    Timber.v("## Uploading clear file")
                    encryptedFile = null
                    fileUploader.uploadFile(
                            file = fileToUpload,
                            filename = renameForMime(attachment.name, newAttachmentAttributes.newMimeType),
                            mimeType = newAttachmentAttributes.newMimeType ?: attachment.getSafeMimeType(),
                            progressListener = progressListener
                    )
                }

                Timber.v("## Update cache storage for ${contentUploadResponse.contentUri}")
                try {
                    fileService.storeDataFor(
                            mxcUrl = contentUploadResponse.contentUri,
                            filename = renameForMime(params.attachment.name, newAttachmentAttributes.newMimeType),
                            mimeType = newAttachmentAttributes.newMimeType ?: params.attachment.getSafeMimeType(),
                            // Cache the bytes we actually uploaded, not the original — otherwise the
                            // sender's timeline serves the pre-compression file from cache while
                            // remote clients download the compressed one.
                            originalFile = fileToUpload,
                            encryptedFile = encryptedFile
                    )
                    Timber.v("## cache storage updated")
                } catch (failure: Throwable) {
                    Timber.e(failure, "## Failed to update file cache")
                }

                // Picked audio uses a MediaStore URI we don't own — let the delete fail quietly.
                if (params.attachment.type == ContentAttachmentData.Type.VOICE_MESSAGE) {
                    tryOrNull("Failed to delete voice message source") {
                        context.contentResolver.delete(params.attachment.queryUriAndroid, null, null)
                    }
                }

                val uploadThumbnailResult = dealWithThumbnail(params, transcodedVideoFile)
                val imageBlurHash = if (attachment.type == ContentAttachmentData.Type.IMAGE) {
                    encodeBlurHashFromImage(fileToUpload)
                } else {
                    null
                }

                handleSuccess(
                        params,
                        contentUploadResponse.contentUri,
                        uploadedFileEncryptedFileInfo,
                        uploadThumbnailResult,
                        imageBlurHash,
                        audioWaveform,
                        newAttachmentAttributes
                )
            } catch (t: Throwable) {
                Timber.e(t, "## ERROR ${t.localizedMessage}")
                handleFailure(params, t)
            }
        } catch (e: Exception) {
            Timber.e(e, "## ERROR")
            handleFailure(params, e)
        } finally {
            // Delete all temporary files
            filesToDelete.forEach {
                tryOrNull { it.delete() }
            }
        }
    }

    private data class VideoCompressOutcome(
            val fileToUpload: File,
            val attributes: NewAttachmentAttributes,
            val transcodedFile: File?,
    )

    private suspend fun compressVideo(
            params: Params,
            initialAttributes: NewAttachmentAttributes,
            filesToDelete: HashSet<File>,
            fallbackToWorkingFile: suspend () -> File,
            stripMetadata: Boolean,
    ): VideoCompressOutcome {
        val progressListener = object : ProgressListener {
            override fun onProgress(progress: Int, total: Int) {
                notifyTracker(params) { contentUploadStateTracker.setCompressingVideo(it, progress.toFloat()) }
            }
        }
        val attachment = params.attachment
        val result = videoCompressor.compress(
                attachment.queryUriAndroid,
                attachment.size,
                targetWidth = attachment.compressionWidth,
                targetHeight = attachment.compressionHeight,
                targetBitrate = attachment.compressionQuality?.let { videoBitrateFor(it) },
                progressListener = progressListener,
        )
        return when (result) {
            is VideoCompressionResult.Success -> {
                // Transcoding produces a fresh container with no source metadata atoms.
                val compressedFile = result.compressedFile.also { filesToDelete.add(it) }
                val (w, h) = readCompressedVideoDimensions(compressedFile)
                VideoCompressOutcome(
                        fileToUpload = compressedFile,
                        attributes = initialAttributes.copy(
                                newFileSize = compressedFile.length(),
                                newWidth = w ?: initialAttributes.newWidth,
                                newHeight = h ?: initialAttributes.newHeight,
                        ),
                        transcodedFile = compressedFile,
                )
            }
            VideoCompressionResult.CompressionNotNeeded,
            VideoCompressionResult.CompressionCancelled ->
                originalVideoOutcome(fallbackToWorkingFile(), initialAttributes, filesToDelete, stripMetadata)
            is VideoCompressionResult.CompressionFailed -> {
                Timber.e(result.failure, "Video compression failed")
                originalVideoOutcome(fallbackToWorkingFile(), initialAttributes, filesToDelete, stripMetadata)
            }
        }
    }

    // Compression was skipped/failed, so the untouched original would otherwise carry its metadata
    // atoms — re-mux to drop them when stripping is enabled.
    private suspend fun originalVideoOutcome(
            working: File,
            attributes: NewAttachmentAttributes,
            filesToDelete: HashSet<File>,
            stripMetadata: Boolean,
    ): VideoCompressOutcome {
        val file = if (stripMetadata) {
            videoMetadataStripper.strip(working)?.also { filesToDelete.add(it) } ?: working
        } else {
            working
        }
        val attrs = if (file !== working) attributes.copy(newFileSize = file.length()) else attributes
        return VideoCompressOutcome(file, attrs, transcodedFile = null)
    }

    private fun measureImageAttributes(file: File, mimeType: String?): NewAttachmentAttributes {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
        return NewAttachmentAttributes(
                newWidth = options.outWidth,
                newHeight = options.outHeight,
                newFileSize = file.length(),
                newMimeType = mimeType,
        )
    }

    private fun readCompressedVideoDimensions(file: File): Pair<Int?, Int?> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
            val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            // METADATA_KEY_VIDEO_WIDTH/HEIGHT return raw track dims; swap when rotation is
            // sideways so layout uses display orientation.
            val swap = rotation == 90 || rotation == 270
            (if (swap) rawH else rawW) to (if (swap) rawW else rawH)
        } catch (e: Exception) {
            Timber.w(e, "Failed to read compressed video dimensions")
            null to null
        } finally {
            retriever.release()
        }
    }

    private data class UploadThumbnailResult(
            val uploadedThumbnailUrl: String,
            val uploadedThumbnailEncryptedFileInfo: EncryptedFileInfo?,
            val width: Int,
            val height: Int,
            val size: Long,
            val blurHash: String?,
    )

    /**
     * If appropriate, it will create and upload a thumbnail.
     */
    private suspend fun dealWithThumbnail(params: Params, transcodedVideo: File?): UploadThumbnailResult? {
        // Prefer the post-transcode file so the thumbnail aspect ratio matches what the player
        // will actually show; otherwise the bubble placeholder ends up the wrong shape.
        val thumbnailData = transcodedVideo?.let { thumbnailExtractor.extractVideoThumbnailFromFile(it) }
                ?: thumbnailExtractor.extractThumbnail(params.attachment)
                ?: return null
        val thumbnailProgressListener = object : ProgressRequestBody.Listener {
            override fun onProgress(current: Long, total: Long) {
                notifyTracker(params) { contentUploadStateTracker.setProgressThumbnail(it, current, total) }
            }
        }
        return try {
            if (params.isEncrypted) {
                Timber.v("Encrypt thumbnail")
                notifyTracker(params) { contentUploadStateTracker.setEncryptingThumbnail(it) }
                val encryptionResult = MXEncryptedAttachments.encryptAttachment(thumbnailData.bytes.inputStream(), clock)
                val contentUploadResponse = fileUploader.uploadByteArray(
                        byteArray = encryptionResult.encryptedByteArray,
                        filename = null,
                        mimeType = MimeTypes.OctetStream,
                        progressListener = thumbnailProgressListener
                )
                UploadThumbnailResult(
                        uploadedThumbnailUrl = contentUploadResponse.contentUri,
                        uploadedThumbnailEncryptedFileInfo = encryptionResult.encryptedFileInfo,
                        width = thumbnailData.width,
                        height = thumbnailData.height,
                        size = thumbnailData.size,
                        blurHash = thumbnailData.blurHash,
                )
            } else {
                val contentUploadResponse = fileUploader.uploadByteArray(
                        byteArray = thumbnailData.bytes,
                        filename = "thumb_${params.attachment.name}",
                        mimeType = thumbnailData.mimeType,
                        progressListener = thumbnailProgressListener
                )
                UploadThumbnailResult(
                        uploadedThumbnailUrl = contentUploadResponse.contentUri,
                        uploadedThumbnailEncryptedFileInfo = null,
                        width = thumbnailData.width,
                        height = thumbnailData.height,
                        size = thumbnailData.size,
                        blurHash = thumbnailData.blurHash,
                )
            }
        } catch (t: Throwable) {
            Timber.e(t, "Thumbnail upload failed")
            null
        }
    }

    private fun handleFailure(params: Params, failure: Throwable): Result {
        notifyTracker(params) { contentUploadStateTracker.setFailure(it, failure) }

        return Result.success(
                WorkerParamsFactory.toData(
                        params.copy(
                                lastFailureMessage = failure.toMatrixErrorStr()
                        )
                )
        )
    }

    private suspend fun handleSuccess(
            params: Params,
            attachmentUrl: String,
            encryptedFileInfo: EncryptedFileInfo?,
            thumbnail: UploadThumbnailResult?,
            imageBlurHash: String?,
            audioWaveform: List<Int>?,
            newAttachmentAttributes: NewAttachmentAttributes
    ): Result {
        notifyTracker(params) { contentUploadStateTracker.setSuccess(it) }
        params.localEchoIds.forEach {
            updateEvent(it.eventId, attachmentUrl, encryptedFileInfo, thumbnail, imageBlurHash, audioWaveform, newAttachmentAttributes)
        }

        val sendParams = MultipleEventSendingDispatcherWorkerParams(
                sessionId = params.sessionId,
                localEchoIds = params.localEchoIds,
                isEncrypted = params.isEncrypted
        )
        return Result.success(WorkerParamsFactory.toData(sendParams)).also {
            Timber.v("## handleSuccess $attachmentUrl, work is stopped $isStopped")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.revokeUriPermission(context.packageName, params.attachment.queryUriAndroid, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                context.revokeUriPermission(params.attachment.queryUriAndroid, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private suspend fun updateEvent(
            eventId: String,
            url: String,
            encryptedFileInfo: EncryptedFileInfo?,
            thumbnail: UploadThumbnailResult?,
            imageBlurHash: String?,
            audioWaveform: List<Int>?,
            newAttachmentAttributes: NewAttachmentAttributes
    ) {
        localEchoRepository.updateEcho(eventId) { event ->
            val content: Content? = event.asDomain(castJsonNumbers = true).content
            val messageContent: MessageContent? = content.toModel()
            // Retrieve potential additional content from the original event
            val additionalContent = content.orEmpty() - messageContent?.toContent().orEmpty().keys
            val updatedContent = when (messageContent) {
                is MessageImageContent -> messageContent.update(url, encryptedFileInfo, imageBlurHash, newAttachmentAttributes)
                is MessageVideoContent -> messageContent.update(url, encryptedFileInfo, thumbnail, newAttachmentAttributes)
                is MessageFileContent -> messageContent.update(url, encryptedFileInfo, newAttachmentAttributes.newFileSize)
                is MessageAudioContent -> messageContent.update(url, encryptedFileInfo, newAttachmentAttributes.newFileSize, audioWaveform)
                else -> messageContent
            }
            event.content = ContentMapper.map(updatedContent.toContent().plus(additionalContent))
        }
    }

    private fun notifyTracker(params: Params, function: (String) -> Unit) {
        params.localEchoIds.forEach { function.invoke(it.eventId) }
    }

    private fun MessageImageContent.update(
            url: String,
            encryptedFileInfo: EncryptedFileInfo?,
            blurHash: String?,
            newAttachmentAttributes: NewAttachmentAttributes?
    ): MessageImageContent {
        val newMime = newAttachmentAttributes?.newMimeType
        // When the mime changed (e.g. JPEG -> WebP after compression), swap the extension on
        // whichever field holds the user-visible filename. If `filename` is set, body is the
        // caption and shouldn't be rewritten.
        val rewriteBody = newMime != null && filename == null
        val rewriteFilename = newMime != null && filename != null
        return copy(
                url = if (encryptedFileInfo == null) url else null,
                encryptedFileInfo = encryptedFileInfo?.copy(url = url),
                body = if (rewriteBody) renameForMime(body, newMime) ?: body else body,
                filename = if (rewriteFilename) renameForMime(filename, newMime) else filename,
                info = info?.let { info ->
                    info.copy(
                            width = newAttachmentAttributes?.newWidth ?: info.width,
                            height = newAttachmentAttributes?.newHeight ?: info.height,
                            size = newAttachmentAttributes?.newFileSize ?: info.size,
                            mimeType = newMime ?: info.mimeType,
                            blurHash = blurHash ?: info.blurHash,
                    )
                }
        )
    }

    private fun renameForMime(name: String?, mimeType: String?): String? {
        if (name == null) return null
        val ext = when (mimeType) {
            "image/webp" -> "webp"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            else -> return name
        }
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        return "$base.$ext"
    }

    private fun MessageVideoContent.update(
            url: String,
            encryptedFileInfo: EncryptedFileInfo?,
            thumbnail: UploadThumbnailResult?,
            newAttachmentAttributes: NewAttachmentAttributes?
    ): MessageVideoContent {
        val thumbnailUrl = thumbnail?.uploadedThumbnailUrl
        val thumbnailEncryptedFileInfo = thumbnail?.uploadedThumbnailEncryptedFileInfo
        return copy(
                url = if (encryptedFileInfo == null) url else null,
                encryptedFileInfo = encryptedFileInfo?.copy(url = url),
                videoInfo = videoInfo?.let { videoInfo ->
                    videoInfo.copy(
                            thumbnailUrl = if (thumbnailEncryptedFileInfo == null) thumbnailUrl else null,
                            thumbnailFile = thumbnailEncryptedFileInfo?.copy(url = thumbnailUrl),
                            width = newAttachmentAttributes?.newWidth ?: videoInfo.width,
                            height = newAttachmentAttributes?.newHeight ?: videoInfo.height,
                            size = newAttachmentAttributes?.newFileSize ?: videoInfo.size,
                            blurHash = thumbnail?.blurHash ?: videoInfo.blurHash,
                            thumbnailInfo = videoInfo.thumbnailInfo?.let { thumbnailInfo ->
                                thumbnailInfo.copy(
                                        width = thumbnail?.width ?: thumbnailInfo.width,
                                        height = thumbnail?.height ?: thumbnailInfo.height,
                                        size = thumbnail?.size ?: thumbnailInfo.size,
                                )
                            },
                    )
                }
        )
    }

    private fun MessageFileContent.update(
            url: String,
            encryptedFileInfo: EncryptedFileInfo?,
            size: Long
    ): MessageFileContent {
        return copy(
                url = if (encryptedFileInfo == null) url else null,
                encryptedFileInfo = encryptedFileInfo?.copy(url = url),
                info = info?.copy(size = size)
        )
    }

    private fun MessageAudioContent.update(
            url: String,
            encryptedFileInfo: EncryptedFileInfo?,
            size: Long,
            waveform: List<Int>?,
    ): MessageAudioContent {
        val updatedWaveformInfo = waveform?.let { scaleWaveformToSpec(it) }?.let {
            (audioWaveformInfo ?: AudioWaveformInfo()).copy(waveform = it)
        } ?: audioWaveformInfo
        return copy(
                url = if (encryptedFileInfo == null) url else null,
                encryptedFileInfo = encryptedFileInfo?.copy(url = url),
                audioInfo = audioInfo?.copy(size = size),
                audioWaveformInfo = updatedWaveformInfo,
        )
    }

    // Matrix MSC2516 caps waveform values at 1024; the extractor returns raw 16-bit PCM peaks.
    private fun scaleWaveformToSpec(raw: List<Int>): List<Int> {
        val max = raw.maxOrNull() ?: return raw
        if (max <= 0) return raw
        return raw.map { (it.toLong() * 1024L / max).toInt().coerceIn(0, 1024) }
    }

    private fun encodeBlurHashFromImage(file: File): String? {
        return try {
            val bitmap = decodeForBlurHash(file) ?: return null
            try {
                val (xc, yc) = blurHashComponents(bitmap.width, bitmap.height)
                BlurHash.encode(bitmap, xc, yc)
            } finally {
                bitmap.recycle()
            }
        } catch (t: Throwable) {
            Timber.w(t, "Failed to encode blurhash")
            null
        }
    }

    private fun decodeForBlurHash(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val sample = generateSequence(1) { it * 2 }
                    .first { it * BLURHASH_DECODE_MAX >= maxOf(bounds.outWidth, bounds.outHeight) }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            return file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
        }
        return when {
            isXpm(file) -> XpmBitmapReader.decode(file)?.let(::downscaleForBlurHash)
            isFarbfeld(file) -> FarbfeldBitmapReader.decode(file)?.let(::downscaleForBlurHash)
            else -> null
        }
    }

    private fun isXpm(file: File): Boolean {
        val head = ByteArray(9)
        val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
        return read >= 9 && String(head, 0, 9, Charsets.US_ASCII) == "/* XPM */"
    }

    private fun isFarbfeld(file: File): Boolean {
        val head = ByteArray(8)
        val read = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
        return read >= 8 && String(head, 0, 8, Charsets.US_ASCII) == "farbfeld"
    }

    private fun downscaleForBlurHash(bitmap: Bitmap): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= BLURHASH_DECODE_MAX) return bitmap
        val scale = BLURHASH_DECODE_MAX.toFloat() / largest
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    companion object {
        private const val MAX_IMAGE_SIZE = 640

        /**
         * The slider is anchored so that [STANDARD_QUALITY] reproduces exactly what compression did
         * before it existed; below that trades quality for size, above it the other way.
         */
        private const val STANDARD_QUALITY = 70
        private const val MIN_IMAGE_QUALITY = 40
        private const val STANDARD_IMAGE_QUALITY = 80
        private const val MIN_VIDEO_BITRATE = 200_000
        private const val STANDARD_VIDEO_BITRATE = 2_000_000
        private const val MAX_VIDEO_BITRATE = 8_000_000

        private fun imageQualityFor(quality: Int) =
                anchoredScale(quality, MIN_IMAGE_QUALITY, STANDARD_IMAGE_QUALITY, 100)

        private fun videoBitrateFor(quality: Int) =
                anchoredScale(quality, MIN_VIDEO_BITRATE, STANDARD_VIDEO_BITRATE, MAX_VIDEO_BITRATE)

        private fun anchoredScale(quality: Int, low: Int, standard: Int, high: Int): Int {
            val clamped = quality.coerceIn(0, 100)
            return if (clamped <= STANDARD_QUALITY) {
                low + (standard - low) * clamped / STANDARD_QUALITY
            } else {
                standard + (high - standard) * (clamped - STANDARD_QUALITY) / (100 - STANDARD_QUALITY)
            }
        }
        private const val BLURHASH_DECODE_MAX = 128
    }
}
