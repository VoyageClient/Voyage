/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.failure.shouldBeRetried
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.internal.network.ProgressRequestBody
import org.matrix.android.sdk.internal.session.room.send.CancelSendTracker
import org.matrix.android.sdk.internal.session.room.send.LocalEchoRepository
import org.matrix.android.sdk.internal.util.toMatrixErrorStr
import org.matrix.android.sdk.internal.worker.BackgroundTaskBody
import org.matrix.android.sdk.internal.worker.BackgroundTaskContext
import org.matrix.android.sdk.internal.worker.BackgroundTaskOutcome
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Sends the bytes of a content URI that the content upload reserved through MSC2246, after the
 * event referencing it has already been dispatched.
 */
internal class UploadMediaBytesTaskBody @Inject constructor(
        private val fileUploader: FileUploader,
        private val fileService: UploadedMediaCache,
        private val contentUploadStateTracker: DefaultContentUploadStateTracker,
        private val cancelSendTracker: CancelSendTracker,
        private val pendingMediaUploadRegistry: PendingMediaUploadRegistry,
        private val localEchoRepository: LocalEchoRepository,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
) : BackgroundTaskBody<UploadMediaBytesWorkerParams> {

    override suspend fun execute(params: UploadMediaBytesWorkerParams, context: BackgroundTaskContext): BackgroundTaskOutcome {
        val clearFile = File(params.clearFilePath)
        val encryptedFile = params.encryptedFilePath?.let { File(it) }

        // Only an explicit cancel discards the bytes. isStopped alone must not: WorkManager also sets it
        // for a lost constraint or a system stop, and then reschedules us — throwing the bytes away there
        // would leave the already-sent event pointing at media that can never arrive.
        if (params.localEchoIds.all { cancelSendTracker.isCancelRequestedFor(it.eventId, it.roomId) }) {
            discard(params, clearFile, encryptedFile)
            return BackgroundTaskOutcome.Success
        }
        if (context.isStopped) {
            if (context.attemptCount < MAX_ATTEMPTS) return BackgroundTaskOutcome.Retry
            onUploadSettled(params, clearFile, encryptedFile)
            notifyFailure(params, IllegalStateException("Media byte upload kept being stopped"))
            return BackgroundTaskOutcome.Success
        }

        val bytesToUpload = encryptedFile ?: clearFile
        if (!bytesToUpload.exists()) {
            Timber.e("## Reserved media ${params.contentUri} has no local bytes left, cannot upload it")
            notifyFailure(params, IllegalStateException("Media bytes are no longer available"))
            return BackgroundTaskOutcome.Success
        }

        return try {
            fileUploader.uploadReservedFile(
                    contentUri = params.contentUri,
                    file = bytesToUpload,
                    // The bytes of an encrypted attachment are opaque, exactly as on the synchronous path.
                    filename = params.filename.takeUnless { params.isEncrypted },
                    mimeType = if (params.isEncrypted) MimeTypes.OctetStream else params.mimeType,
                    progressListener = object : ProgressRequestBody.Listener {
                        override fun onProgress(current: Long, total: Long) {
                            params.localEchoIds.forEach { notifyProgress(params, it.eventId, current, total) }
                        }
                    },
            )
            onUploadSettled(params, clearFile, encryptedFile)
            notifySettledSuccess(params)
            BackgroundTaskOutcome.Success
        } catch (failure: CancellationException) {
            // Never treat a stop mid-PUT as an upload error; the files stay put for the next run.
            throw failure
        } catch (failure: Throwable) {
            // The server already has these bytes; nothing more to do.
            if (failure is Failure.ServerError && failure.error.code == MatrixError.M_CANNOT_OVERWRITE_MEDIA) {
                onUploadSettled(params, clearFile, encryptedFile)
                notifySettledSuccess(params)
                return BackgroundTaskOutcome.Success
            }
            if (failure.shouldBeRetried() && context.attemptCount < MAX_ATTEMPTS) {
                Timber.w(failure, "## Media byte upload failed, will retry (attempt ${context.attemptCount})")
                BackgroundTaskOutcome.Retry
            } else {
                // Giving up on the server copy, but the sender's own timeline should still have the
                // media, so cache it before dropping the temporary files.
                Timber.e(failure, "## Media byte upload permanently failed for ${params.contentUri}")
                onUploadSettled(params, clearFile, encryptedFile)
                notifyFailure(params, failure)
                BackgroundTaskOutcome.Success
            }
        }
    }

    private fun notifyProgress(params: UploadMediaBytesWorkerParams, eventId: String, current: Long, total: Long) {
        val index = params.galleryItemIndex
        val sizes = params.galleryItemSizes
        if (index != null && !sizes.isNullOrEmpty() && index in sizes.indices) {
            contentUploadStateTracker.setGalleryProgress(eventId, index, sizes, current, total)
        } else {
            contentUploadStateTracker.setProgress(eventId, current, total)
        }
    }

    /** A gallery's overlay only comes down once every item has landed, in whatever order they do. */
    private fun notifySettledSuccess(params: UploadMediaBytesWorkerParams) {
        val index = params.galleryItemIndex
        val sizes = params.galleryItemSizes
        if (index == null || sizes == null) {
            params.localEchoIds.forEach { contentUploadStateTracker.setSuccess(it.eventId) }
        } else {
            params.localEchoIds.forEach { contentUploadStateTracker.setGalleryItemSettled(it.eventId, index, sizes) }
        }
    }

    /**
     * Move the bytes into the file cache now that the event is long gone, so the sender's own copy
     * survives, then stop redirecting rendering at the temporary files and drop them.
     */
    private suspend fun onUploadSettled(params: UploadMediaBytesWorkerParams, clearFile: File, encryptedFile: File?) {
        if (clearFile.exists()) {
            try {
                withContext(coroutineDispatchers.io) {
                    fileService.storeDataFor(
                            mxcUrl = params.contentUri,
                            filename = params.filename,
                            mimeType = params.mimeType,
                            originalFile = clearFile,
                            encryptedFile = encryptedFile,
                    )
                }
            } catch (failure: Throwable) {
                // Only costs a re-download of our own media.
                Timber.w(failure, "## Failed to cache uploaded media ${params.contentUri}")
            }
        }
        discard(params, clearFile, encryptedFile)
    }

    private fun discard(params: UploadMediaBytesWorkerParams, clearFile: File, encryptedFile: File?) {
        pendingMediaUploadRegistry.clear(params.contentUri)
        tryOrNull { clearFile.delete() }
        tryOrNull { encryptedFile?.delete() }
    }

    private fun notifyFailure(params: UploadMediaBytesWorkerParams, failure: Throwable) {
        pendingMediaUploadRegistry.clear(params.contentUri)
        params.localEchoIds.forEach { contentUploadStateTracker.setFailure(it.eventId, failure) }
        if (params.galleryItemIndex == null) return
        // One item's media is gone, so the gallery event is not deliverable; this is also what the
        // remaining items' upload workers check before doing any more work.
        params.localEchoIds.forEach {
            localEchoRepository.updateSendState(it.eventId, it.roomId, SendState.UNDELIVERED, failure.toMatrixErrorStr())
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
    }
}
