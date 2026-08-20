/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import com.vanniktech.blurhash.BlurHash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
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
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageStickerContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.model.message.coerceGalleryJsonNumbers
import org.matrix.android.sdk.api.session.room.model.message.galleryFallbackBody
import org.matrix.android.sdk.api.session.room.model.message.toAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.toAttachmentContents
import org.matrix.android.sdk.api.session.room.model.message.toGalleryItem
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.settings.LightweightSettingsStorage
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.api.util.JxlSupport
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.internal.crypto.attachments.MXEncryptedAttachments
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.network.ProgressRequestBody
import org.matrix.android.sdk.internal.platform.BackgroundQueuePolicy
import org.matrix.android.sdk.internal.platform.BackgroundTaskScheduler
import org.matrix.android.sdk.internal.platform.BackgroundTaskType
import org.matrix.android.sdk.internal.platform.backgroundTask
import org.matrix.android.sdk.internal.session.DefaultFileService
import org.matrix.android.sdk.internal.session.room.send.CancelSendTracker
import org.matrix.android.sdk.internal.session.room.send.LocalEchoRepository
import org.matrix.android.sdk.internal.session.room.send.MultipleEventSendingDispatcherWorkerParams
import org.matrix.android.sdk.internal.session.room.send.uploadWorkTag
import org.matrix.android.sdk.internal.util.TemporaryFileCreator
import org.matrix.android.sdk.internal.util.time.Clock
import org.matrix.android.sdk.internal.util.toMatrixErrorStr
import org.matrix.android.sdk.internal.worker.BackgroundTaskBody
import org.matrix.android.sdk.internal.worker.BackgroundTaskContext
import org.matrix.android.sdk.internal.worker.BackgroundTaskOutcome
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** A content URI reserved through MSC2246 whose bytes still have to be sent. */
private data class DeferredUpload(
        val contentUri: String,
        val filename: String?,
        val mimeType: String?,
        val isEncrypted: Boolean,
        val clearFilePath: String,
        val encryptedFilePath: String?,
)

private data class NewAttachmentAttributes(
        val newWidth: Int? = null,
        val newHeight: Int? = null,
        val newFileSize: Long,
        val newMimeType: String? = null,
        val newIsAnimated: Boolean? = null,
)

/**
 * Possible previous worker: None.
 * Possible next worker    : Always [MultipleEventSendingDispatcherWorker].
 */
private typealias Params = UploadContentWorkerParams

internal class UploadContentTaskBody @Inject constructor(
        private val appContext: Context,
        private val fileUploader: FileUploader,
        private val contentUploadStateTracker: DefaultContentUploadStateTracker,
        private val fileService: DefaultFileService,
        private val cancelSendTracker: CancelSendTracker,
        private val imageCompressor: ImageCompressor,
        private val imageExitTagRemover: ImageExifTagRemover,
        private val videoMetadataStripper: VideoMetadataStripper,
        private val videoCompressor: VideoCompressor,
        private val lightweightSettingsStorage: LightweightSettingsStorage,
        private val thumbnailExtractor: ThumbnailExtractor,
        private val localEchoRepository: LocalEchoRepository,
        private val temporaryFileCreator: TemporaryFileCreator,
        private val clock: Clock,
        private val backgroundTaskScheduler: BackgroundTaskScheduler,
        private val pendingMediaUploadRegistry: PendingMediaUploadRegistry,
) : BackgroundTaskBody<UploadContentWorkerParams> {

    override suspend fun execute(params: Params, context: BackgroundTaskContext): BackgroundTaskOutcome {
        Timber.v("Starting upload media work with params $params")
        // Just defensive code to ensure that we never have an uncaught exception that could break the queue
        return try {
            internalDoWork(params, context)
        } catch (failure: Throwable) {
            Timber.e(failure)
            handleFailure(params, failure)
        }
    }

    /**
     * The user asked for this send to go away — as opposed to [BackgroundTaskContext.isStopped], which only means the scheduler
     * took the task away (lost constraint, doze, system pressure) and will run it again.
     */
    private fun isCancelledByUser(params: Params): Boolean =
            params.localEchoIds.all { cancelSendTracker.isCancelRequestedFor(it.eventId, it.roomId) }

    private suspend fun internalDoWork(params: Params, context: BackgroundTaskContext): BackgroundTaskOutcome {
        // Failing kills the chain for good and strands the echo at "Waiting…" with nothing to retry it,
        // so only a real cancel may do that; a system stop has to come back as a retry.
        if (context.isStopped && !isCancelledByUser(params)) return BackgroundTaskOutcome.Retry
        if (isCancelledByUser(params)) {
            Timber.e("## Send: Work cancelled by user")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.revokeUriPermission(appContext.packageName, params.attachment.queryUriAndroid, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                appContext.revokeUriPermission(params.attachment.queryUriAndroid, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return BackgroundTaskOutcome.Failure
        }

        if (params.galleryItemIndex != null && isGalleryAlreadyFailed(params)) {
            // An earlier item of the same gallery failed; don't waste the upload, fail the event.
            return handleFailure(params, Throwable("An earlier item of this gallery failed to upload"))
        }

        val attachment = params.attachment
        val filesToDelete = hashSetOf<File>()
        // Candidates to hand to UploadMediaBytesWorker, which deletes them once uploaded and cached.
        // Only spared below once that worker is actually queued, so a failure in between can't strand them.
        val filesToKeep = hashSetOf<File>()
        var handedOver = false

        return try {
            // Materialize the source to a local temp file. Deferred until first access so the
            // video+compress happy path (which feeds the source URI straight into Media3) never
            // pays the cost of copying a multi-hundred-MB file.
            var cachedWorkingFile: File? = null
            suspend fun workingFile(): File {
                cachedWorkingFile?.let { return it }
                val f = temporaryFileCreator.create().also { filesToDelete.add(it) }
                val input = appContext.contentResolver.openInputStream(attachment.queryUriAndroid)
                        ?: throw IOException("Cannot openInputStream for file: ${attachment.queryUri}")
                input.use { inStream -> f.outputStream().use { inStream.copyTo(it) } }
                cachedWorkingFile = f
                return f
            }

            val progressListener = object : ProgressRequestBody.Listener {
                override fun onProgress(current: Long, total: Long) {
                    if (context.isStopped) return
                    notifyItemPhase(params, current, total) { contentUploadStateTracker.setProgress(it, current, total) }
                }
            }

            var uploadedFileEncryptedFileInfo: EncryptedFileInfo? = null

            try {
                val fileToUpload: File
                var transcodedVideoFile: File? = null
                var waveformDeferred: Deferred<List<Int>?>? = null
                var newAttachmentAttributes = NewAttachmentAttributes(
                        params.attachment.width?.toInt(),
                        params.attachment.height?.toInt(),
                        params.attachment.size
                )

                // The room this is going to may have its own answer; the account's is the default.
                val stripMetadata = attachment.stripMetadata ?: lightweightSettingsStorage.shouldStripMediaMetadata()

                // Anything the sender chose is honoured even at "original size", which only means
                // "don't apply the automatic downscale".
                // "Original size" is chosen per attachment, so it overrides the send's own answer.
                val compressThisOne = (params.compressBeforeSending || attachment.hasCustomCompression) &&
                        !(attachment.keepOriginalSize && !attachment.hasCustomCompression)
                if (attachment.type == ContentAttachmentData.Type.IMAGE && compressThisOne) {
                    notifyItemPhase(params, 0L, 0L) { contentUploadStateTracker.setCompressingImage(it) }

                    val compressed = imageCompressor.compress(
                            workingFile(),
                            attachment.compressionWidth ?: MAX_IMAGE_SIZE,
                            attachment.compressionHeight ?: MAX_IMAGE_SIZE,
                            imageQualityFor(attachment.compressionQuality ?: STANDARD_QUALITY),
                            exactSize = attachment.compressionWidth != null,
                    )
                    fileToUpload = compressed.file.also { filesToDelete.add(it) }
                    newAttachmentAttributes = measureImageAttributes(fileToUpload, compressed.mimeType)
                } else if (attachment.type == ContentAttachmentData.Type.VIDEO && compressThisOne) {
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
                        notifyItemPhase(params, 0L, 0L) { contentUploadStateTracker.setCompressingImage(it) }
                        val reEncoded = imageCompressor.reEncodeStrippingMetadata(working)
                        fileToUpload = reEncoded.file.also { if (it !== working) filesToDelete.add(it) }
                        newAttachmentAttributes = if (reEncoded.mimeType != null) {
                            measureImageAttributes(fileToUpload, reEncoded.mimeType)
                        } else {
                            newAttachmentAttributes.copy(newFileSize = fileToUpload.length())
                        }
                    }
                } else {
                    // A JPEG/PNG/WebP shared through the file/document picker lands here rather than the
                    // IMAGE branch; scrub it too so "send as file" doesn't leak EXIF. Videos sent at
                    // original size are re-muxed to drop their location/metadata atoms.
                    val strippedVideo = if (stripMetadata && attachment.type == ContentAttachmentData.Type.VIDEO) {
                        // Re-mux from the source URI so the video is read once, not copied to a working
                        // file and then read again — two full passes over a large file before it can send.
                        notifyItemPhase(params, 0L, 0L) { contentUploadStateTracker.setProcessingVideo(it, 0f) }
                        val stripProgress = object : ProgressListener {
                            // Only notify on whole-percent changes: the muxer reports every packet,
                            // and each report posts a main-thread runnable.
                            private var lastPercent = -1
                            override fun onProgress(progress: Int, total: Int) {
                                val percent = if (total > 0) progress * 100 / total else 0
                                if (percent == lastPercent) return
                                lastPercent = percent
                                notifyItemPhase(params, 0L, 0L) {
                                    contentUploadStateTracker.setProcessingVideo(it, percent / 100f)
                                }
                            }
                        }
                        videoMetadataStripper.strip(attachment.queryUriAndroid, stripProgress)
                                ?.also { filesToDelete.add(it) }
                    } else {
                        null
                    }
                    val working = strippedVideo ?: workingFile()
                    fileToUpload = when {
                        strippedVideo != null -> working.also {
                            newAttachmentAttributes = newAttachmentAttributes.copy(newFileSize = it.length())
                        }
                        !stripMetadata -> working
                        attachment.type == ContentAttachmentData.Type.AUDIO ||
                                attachment.type == ContentAttachmentData.Type.VOICE_MESSAGE -> {
                            // Sound carries a title, an artist and its cover art as well as where it
                            // was recorded; only the last of those is worth taking off it.
                            videoMetadataStripper.stripInPlace(working)
                            working
                        }
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
                        notifyItemPhase(params, 0L, 0L) { contentUploadStateTracker.setProcessingAudio(it) }
                        // Reading the peaks decodes the whole file, which the upload has no need to
                        // wait behind: the two run together and the answer is collected when the
                        // event is written. A child of this job, so cancelling the send stops it.
                        val waveformSource = fileToUpload
                        waveformDeferred = CoroutineScope(currentCoroutineContext()).async(Dispatchers.IO) {
                            tryOrNull("## Failed to read the voice message waveform") {
                                AudioWaveformExtractor.extract(waveformSource).takeIf { it.isNotEmpty() }
                            }
                        }
                    }
                    // Fix: OpenableColumns.SIZE may return -1 or 0
                    if (params.attachment.size <= 0) {
                        newAttachmentAttributes = newAttachmentAttributes.copy(newFileSize = fileToUpload.length())
                    }
                }

                // MSC4230: sniff the bytes we are actually about to upload, so a compression pass that
                // flattened or re-encoded the animation is reflected rather than the original's format.
                if (attachment.type == ContentAttachmentData.Type.IMAGE) {
                    val format = sniffImageFormat(fileToUpload)
                    newAttachmentAttributes = newAttachmentAttributes.copy(
                            newIsAnimated = format.isAnimated() ?: animatedJxl(format, fileToUpload)
                    )
                }

                // Compression can take a long time; re-check cancellation here so a cancel that
                // arrived mid-compress short-circuits the chain (failure stops the dispatcher
                // from then trying to send the half-baked event with a local file:// URL).
                if (context.isStopped && !isCancelledByUser(params)) return BackgroundTaskOutcome.Retry
                if (isCancelledByUser(params)) {
                    notifyTracker(params) { contentUploadStateTracker.setFailure(it, Throwable("Cancelled")) }
                    return BackgroundTaskOutcome.Failure
                }

                val encryptedFile: File?
                if (params.isEncrypted) {
                    Timber.v("## Encrypt file")
                    encryptedFile = temporaryFileCreator.create()
                            .also { filesToDelete.add(it) }

                    uploadedFileEncryptedFileInfo =
                            MXEncryptedAttachments.encrypt(fileToUpload.inputStream(), encryptedFile, clock) { read, total ->
                                notifyItemPhase(params, 0L, 0L) {
                                    contentUploadStateTracker.setEncrypting(it, read.toLong(), total.toLong())
                                }
                            }
                } else {
                    encryptedFile = null
                }

                val uploadFilename = if (params.isEncrypted) null else renameForMime(attachment.name, newAttachmentAttributes.newMimeType)
                val uploadMimeType = if (params.isEncrypted) {
                    MimeTypes.OctetStream
                } else {
                    newAttachmentAttributes.newMimeType ?: attachment.getSafeMimeType()
                }
                val bytesToUpload = encryptedFile ?: fileToUpload

                // Reserving skips uploadFile's size guard, and a file the server will refuse must fail
                // before the event goes out rather than after.
                fileUploader.checkUploadSize(bytesToUpload)

                // MSC2246: reserving the content URI first lets the event be sent straight away, with the
                // bytes following on their own queue. Null means the server can't (or won't right now), so
                // fall back to uploading the bytes and only then sending.
                val reserved = tryOrNull("## Failed to reserve a content URI") { fileUploader.createMedia() }
                val contentUri = if (reserved != null) {
                    reserved.contentUri
                } else {
                    Timber.v("## Uploading file synchronously")
                    fileUploader.uploadFile(
                            file = bytesToUpload,
                            filename = uploadFilename,
                            mimeType = uploadMimeType,
                            progressListener = progressListener
                    ).contentUri
                }

                val cacheFilename = renameForMime(params.attachment.name, newAttachmentAttributes.newMimeType)
                val cacheMimeType = newAttachmentAttributes.newMimeType ?: params.attachment.getSafeMimeType()

                if (reserved == null) {
                    Timber.v("## Update cache storage for $contentUri")
                    try {
                        fileService.storeDataFor(
                                mxcUrl = contentUri,
                                filename = cacheFilename,
                                mimeType = cacheMimeType,
                                // Cache the bytes we actually uploaded, not the original — otherwise the
                                // sender's timeline serves the pre-compression file from cache while
                                // remote clients download the compressed one.
                                originalFile = fileToUpload,
                                encryptedFile = encryptedFile
                        )
                    } catch (failure: Throwable) {
                        Timber.e(failure, "## Failed to update file cache")
                    }
                } else {
                    // Caching means copying the whole file, so it waits until after the event has gone
                    // out; UploadMediaBytesWorker does it, and owns these files from here on.
                    filesToKeep.add(fileToUpload)
                    encryptedFile?.let { filesToKeep.add(it) }
                    // Point the sender's own timeline at the local bytes until the upload lands: the
                    // homeserver has nothing to serve for this URI yet. Always the plaintext, which is
                    // what both the renderer and the file cache want.
                    pendingMediaUploadRegistry.markPending(
                            contentUri = contentUri,
                            localFile = fileToUpload,
                            ownedFiles = listOfNotNull(encryptedFile),
                            eventIds = params.localEchoIds.map { it.eventId }.toSet(),
                    )
                }

                // Picked audio uses a MediaStore URI we don't own — let the delete fail quietly.
                if (params.attachment.type == ContentAttachmentData.Type.VOICE_MESSAGE) {
                    tryOrNull("Failed to delete voice message source") {
                        appContext.contentResolver.delete(params.attachment.queryUriAndroid, null, null)
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
                        contentUri,
                        uploadedFileEncryptedFileInfo,
                        uploadThumbnailResult,
                        imageBlurHash,
                        waveformDeferred?.await(),
                        newAttachmentAttributes,
                        deferredUpload = reserved?.let {
                            DeferredUpload(
                                    contentUri = contentUri,
                                    filename = cacheFilename,
                                    mimeType = cacheMimeType,
                                    isEncrypted = params.isEncrypted,
                                    clearFilePath = fileToUpload.absolutePath,
                                    encryptedFilePath = encryptedFile?.absolutePath,
                            )
                        },
                        onHandedOver = { handedOver = it },
                        context = context,
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
            (if (handedOver) filesToDelete - filesToKeep else filesToDelete).forEach {
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
                notifyItemPhase(params, 0L, 0L) { contentUploadStateTracker.setCompressingVideo(it, progress.toFloat()) }
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
                                // The muxer only writes MP4, whatever container came in.
                                newMimeType = MimeTypes.Mp4,
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
        // Stripping re-muxes through the same MP4-only muxer, so the container may have changed.
        val attrs = if (file !== working) attributes.copy(newFileSize = file.length(), newMimeType = MimeTypes.Mp4) else attributes
        return VideoCompressOutcome(file, attrs, transcodedFile = null)
    }

    private fun measureImageAttributes(file: File, mimeType: String?): NewAttachmentAttributes {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
        // BitmapFactory reports 0x0 for anything it can't decode, and those zeros would be published
        // as the event's dimensions.
        val measured = if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            measureUndecodableImage(file)
        }
        return NewAttachmentAttributes(
                newWidth = measured?.first ?: options.outWidth,
                newHeight = measured?.second ?: options.outHeight,
                newFileSize = file.length(),
                newMimeType = mimeType,
        )
    }

    /** The JPEG XL signature alone doesn't say, but the frame count does. */
    private fun animatedJxl(format: ImageSourceFormat, file: File): Boolean? {
        if (format != ImageSourceFormat.JXL || !JxlSupport.isAvailable) return null
        return JxlImageReader.frameCount(file)?.let { it > 1 }
    }

    private fun measureUndecodableImage(file: File): Pair<Int, Int>? {
        return if (JxlSupport.isAvailable && sniffImageFormat(file) == ImageSourceFormat.JXL) {
            JxlImageReader.readSize(file)
        } else {
            null
        }
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
        // Frame decode + JPEG encode take seconds on a large video; without a state of their own the
        // progress UI sits on the last processing percent looking hung.
        if (params.attachment.type == ContentAttachmentData.Type.VIDEO) {
            notifyItemPhase(params, 0L, 0L) { contentUploadStateTracker.setPreparingThumbnail(it) }
        }
        // Prefer the post-transcode file so the thumbnail aspect ratio matches what the player
        // will actually show; otherwise the bubble placeholder ends up the wrong shape.
        val thumbnailData = transcodedVideo?.let { thumbnailExtractor.extractVideoThumbnailFromFile(it) }
                ?: thumbnailExtractor.extractThumbnail(params.attachment)
                ?: return null
        val thumbnailProgressListener = object : ProgressRequestBody.Listener {
            override fun onProgress(current: Long, total: Long) {
                notifyItemPhase(params, 0L, 0L) { contentUploadStateTracker.setProgressThumbnail(it, current, total) }
            }
        }
        return try {
            if (params.isEncrypted) {
                Timber.v("Encrypt thumbnail")
                notifyItemPhase(params, 0L, 0L) { contentUploadStateTracker.setEncryptingThumbnail(it) }
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

    private suspend fun isGalleryAlreadyFailed(params: Params): Boolean {
        return params.localEchoIds.any {
            localEchoRepository.getUpToDateEcho(it.eventId)?.sendState == SendState.UNDELIVERED
        }
    }

    private fun handleFailure(params: Params, failure: Throwable): BackgroundTaskOutcome {
        notifyTracker(params) { contentUploadStateTracker.setFailure(it, failure) }
        if (params.galleryItemIndex != null) {
            // Later items of the gallery and the dispatcher check this state to short-circuit.
            params.localEchoIds.forEach {
                localEchoRepository.updateSendState(it.eventId, it.roomId, SendState.UNDELIVERED, failure.toMatrixErrorStr())
            }
        }

        return BackgroundTaskOutcome.SuccessWith(params.copy(lastFailureMessage = failure.toMatrixErrorStr()))
    }

    private suspend fun handleSuccess(
            params: Params,
            attachmentUrl: String,
            encryptedFileInfo: EncryptedFileInfo?,
            thumbnail: UploadThumbnailResult?,
            imageBlurHash: String?,
            audioWaveform: List<Int>?,
            newAttachmentAttributes: NewAttachmentAttributes,
            deferredUpload: DeferredUpload?,
            onHandedOver: (Boolean) -> Unit,
            context: BackgroundTaskContext,
    ): BackgroundTaskOutcome {
        params.localEchoIds.forEach {
            updateEvent(
                    it.eventId, attachmentUrl, encryptedFileInfo, thumbnail, imageBlurHash, audioWaveform, newAttachmentAttributes,
                    params.galleryItemIndex,
            )
        }
        // A deferred upload's bytes are still to come; UploadMediaBytesWorker settles that item.
        if (deferredUpload == null) {
            notifyItemSettled(params)
        }

        deferredUpload?.let { onHandedOver(enqueueByteUpload(params, it)) }

        val sendParams = MultipleEventSendingDispatcherWorkerParams(
                sessionId = params.sessionId,
                localEchoIds = params.localEchoIds,
                isEncrypted = params.isEncrypted
        )
        return BackgroundTaskOutcome.SuccessWith(sendParams).also {
            Timber.v("## handleSuccess $attachmentUrl, work is stopped ${context.isStopped}")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.revokeUriPermission(appContext.packageName, params.attachment.queryUriAndroid, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                appContext.revokeUriPermission(params.attachment.queryUriAndroid, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /** Queued separately from the send chain so the event goes out immediately. */
    private fun enqueueByteUpload(params: Params, deferred: DeferredUpload): Boolean {
        if (params.localEchoIds.isEmpty()) return false
        backgroundTaskScheduler.enqueueUnique(
                UploadMediaBytesWorker.workName(params.sessionId, deferred.contentUri),
                BackgroundQueuePolicy.REPLACE,
                backgroundTask(
                        type = BackgroundTaskType.UPLOAD_MEDIA_BYTES,
                        params = UploadMediaBytesWorkerParams(
                                sessionId = params.sessionId,
                                localEchoIds = params.localEchoIds,
                                contentUri = deferred.contentUri,
                                filename = deferred.filename,
                                mimeType = deferred.mimeType,
                                isEncrypted = deferred.isEncrypted,
                                clearFilePath = deferred.clearFilePath,
                                encryptedFilePath = deferred.encryptedFilePath,
                                galleryItemIndex = params.galleryItemIndex,
                                galleryItemSizes = params.galleryItemSizes,
                        ),
                        matrixConstraints = true,
                        isolateInput = true,
                        extraTags = params.localEchoIds.map { uploadWorkTag(it.eventId) },
                )
        )
        return true
    }

    private suspend fun updateEvent(
            eventId: String,
            url: String,
            encryptedFileInfo: EncryptedFileInfo?,
            thumbnail: UploadThumbnailResult?,
            imageBlurHash: String?,
            audioWaveform: List<Int>?,
            newAttachmentAttributes: NewAttachmentAttributes,
            galleryItemIndex: Int? = null,
    ) {
        localEchoRepository.updateEcho(eventId) { event ->
            val content: Content? = event.asDomain(castJsonNumbers = true).content
            fun Content?.withUploadedMedia(): Content {
                val messageContent: MessageContent? = this.toModel() ?: this.toModel<MessageStickerContent>()
                // Retrieve potential additional content from the original event
                val additionalContent = this.orEmpty() - messageContent?.toContent().orEmpty().keys
                val updatedContent = when (messageContent) {
                    is MessageImageContent -> messageContent.update(url, encryptedFileInfo, imageBlurHash, newAttachmentAttributes)
                    is MessageVideoContent -> messageContent.update(url, encryptedFileInfo, thumbnail, newAttachmentAttributes)
                    is MessageFileContent -> messageContent.update(url, encryptedFileInfo, newAttachmentAttributes.newFileSize)
                    is MessageAudioContent -> messageContent.update(url, encryptedFileInfo, newAttachmentAttributes.newFileSize, audioWaveform)
                    is MessageStickerContent -> messageContent.update(url, encryptedFileInfo, imageBlurHash, newAttachmentAttributes)
                    is MessageGalleryContent -> messageContent
                            .updateItem(galleryItemIndex, url, encryptedFileInfo, thumbnail, imageBlurHash, audioWaveform, newAttachmentAttributes)
                    else -> messageContent
                }
                return updatedContent.toContent().plus(additionalContent)
            }
            // An edit carries the media twice: the copy under m.new_content is the one clients display.
            @Suppress("UNCHECKED_CAST")
            val newContent = (content?.get("m.new_content") as? Content)?.withUploadedMedia()
            val updated = content.withUploadedMedia()
            event.content = ContentMapper.map(if (newContent == null) updated else updated + ("m.new_content" to newContent))
        }
    }

    private fun MessageGalleryContent.updateItem(
            index: Int?,
            url: String,
            encryptedFileInfo: EncryptedFileInfo?,
            thumbnail: UploadThumbnailResult?,
            imageBlurHash: String?,
            audioWaveform: List<Int>?,
            newAttachmentAttributes: NewAttachmentAttributes,
    ): MessageGalleryContent {
        if (index == null || index !in itemtypes.indices) return this
        val item = itemtypes[index].toAttachmentContent() ?: return this
        val updatedItem = when (item) {
            is MessageImageContent -> item.update(url, encryptedFileInfo, imageBlurHash, newAttachmentAttributes)
            is MessageVideoContent -> item.update(url, encryptedFileInfo, thumbnail, newAttachmentAttributes)
            is MessageFileContent -> item.update(url, encryptedFileInfo, newAttachmentAttributes.newFileSize)
            is MessageAudioContent -> item.update(url, encryptedFileInfo, newAttachmentAttributes.newFileSize, audioWaveform)
            else -> item
        }
        val newItemDict = updatedItem.toGalleryItem() ?: return this
        // Sibling items are Doubles after the same Any-adapter round trip.
        @Suppress("UNCHECKED_CAST")
        val newItems = itemtypes.toMutableList().apply { set(index, newItemDict) }
                .map { coerceGalleryJsonNumbers(it) as JsonDict }
        // The fallback body mirrors the items, so it follows their urls; a caption never changes.
        val newBody = if (body == galleryFallbackBody(galleryItems())) galleryFallbackBody(newItems.toAttachmentContents()) else body
        return copy(body = newBody, itemtypes = newItems)
    }

    private fun notifyTracker(params: Params, function: (String) -> Unit) {
        params.localEchoIds.forEach { function.invoke(it.eventId) }
    }

    /** A gallery item folds every phase into the one whole-gallery state; anything else uses [fallback]. */
    private fun notifyItemPhase(params: Params, itemCurrent: Long, itemTotal: Long, fallback: (String) -> Unit) {
        val index = params.galleryItemIndex
        val sizes = params.galleryItemSizes
        if (index == null || sizes.isNullOrEmpty() || index !in sizes.indices) {
            notifyTracker(params, fallback)
            return
        }
        notifyTracker(params) { contentUploadStateTracker.setGalleryProgress(it, index, sizes, itemCurrent, itemTotal) }
    }

    /** The whole send is done, unless this is one item of a gallery whose siblings are still going. */
    private fun notifyItemSettled(params: Params) {
        val index = params.galleryItemIndex
        val sizes = params.galleryItemSizes
        if (index == null || sizes == null) {
            notifyTracker(params) { contentUploadStateTracker.setSuccess(it) }
        } else {
            notifyTracker(params) { contentUploadStateTracker.setGalleryItemSettled(it, index, sizes) }
        }
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
                            isAnimatedStable = newAttachmentAttributes?.newIsAnimated ?: info.isAnimatedStable,
                    )
                }
        )
    }

    private fun renameForMime(name: String?, mimeType: String?): String? = MimeTypes.renameForMimeType(name, mimeType)

    // Only reached by an edit which replaces a sticker: stickers are otherwise sent already uploaded.
    private fun MessageStickerContent.update(
            url: String,
            encryptedFileInfo: EncryptedFileInfo?,
            blurHash: String?,
            newAttachmentAttributes: NewAttachmentAttributes?
    ): MessageStickerContent {
        val newMime = newAttachmentAttributes?.newMimeType
        return copy(
                url = if (encryptedFileInfo == null) url else null,
                encryptedFileInfo = encryptedFileInfo?.copy(url = url),
                body = if (newMime != null && filename == null) renameForMime(body, newMime) ?: body else body,
                filename = if (newMime != null && filename != null) renameForMime(filename, newMime) else filename,
                info = info?.let { info ->
                    info.copy(
                            width = newAttachmentAttributes?.newWidth ?: info.width,
                            height = newAttachmentAttributes?.newHeight ?: info.height,
                            size = newAttachmentAttributes?.newFileSize ?: info.size,
                            mimeType = newMime ?: info.mimeType,
                            blurHash = blurHash ?: info.blurHash,
                            isAnimatedStable = newAttachmentAttributes?.newIsAnimated ?: info.isAnimatedStable,
                    )
                }
        )
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
        return if (JxlSupport.isAvailable && sniffImageFormat(file) == ImageSourceFormat.JXL) {
            JxlImageReader.decode(file, BLURHASH_DECODE_MAX)
        } else {
            null
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
