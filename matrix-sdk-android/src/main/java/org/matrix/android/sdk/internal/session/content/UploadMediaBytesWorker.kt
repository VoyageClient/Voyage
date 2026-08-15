/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import android.content.Context
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.failure.shouldBeRetried
import org.matrix.android.sdk.api.util.MimeTypes
import org.matrix.android.sdk.internal.SessionManager
import org.matrix.android.sdk.internal.network.ProgressRequestBody
import org.matrix.android.sdk.internal.session.DefaultFileService
import org.matrix.android.sdk.internal.session.SessionComponent
import org.matrix.android.sdk.internal.session.room.send.CancelSendTracker
import org.matrix.android.sdk.internal.worker.SessionSafeCoroutineWorker
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * Sends the bytes of a content URI that [UploadContentWorker] reserved through MSC2246, after the
 * event referencing it has already been dispatched.
 *
 * Possible previous worker: none — queued directly by [UploadContentWorker], off the send chain.
 * Possible next worker    : none.
 */
internal class UploadMediaBytesWorker(context: Context, params: WorkerParameters, sessionManager: SessionManager) :
        SessionSafeCoroutineWorker<UploadMediaBytesWorkerParams>(
                context, params, sessionManager, UploadMediaBytesWorkerParams::class.java
        ) {

    @Inject lateinit var fileUploader: FileUploader
    @Inject lateinit var fileService: DefaultFileService
    @Inject lateinit var contentUploadStateTracker: DefaultContentUploadStateTracker
    @Inject lateinit var cancelSendTracker: CancelSendTracker
    @Inject lateinit var pendingMediaUploadRegistry: PendingMediaUploadRegistry
    @Inject lateinit var coroutineDispatchers: MatrixCoroutineDispatchers

    override fun injectWith(injector: SessionComponent) {
        injector.inject(this)
    }

    override fun buildErrorParams(params: UploadMediaBytesWorkerParams, message: String): UploadMediaBytesWorkerParams {
        return params.copy(lastFailureMessage = params.lastFailureMessage ?: message)
    }

    override suspend fun doSafeWork(params: UploadMediaBytesWorkerParams): Result {
        val clearFile = File(params.clearFilePath)
        val encryptedFile = params.encryptedFilePath?.let { File(it) }

        // Only an explicit cancel discards the bytes. isStopped alone must not: WorkManager also sets it
        // for a lost constraint or a system stop, and then reschedules us — throwing the bytes away there
        // would leave the already-sent event pointing at media that can never arrive.
        if (params.localEchoIds.all { cancelSendTracker.isCancelRequestedFor(it.eventId, it.roomId) }) {
            discard(params, clearFile, encryptedFile)
            return Result.success()
        }
        if (isStopped) {
            if (runAttemptCount < MAX_ATTEMPTS) return Result.retry()
            onUploadSettled(params, clearFile, encryptedFile)
            notifyFailure(params, IllegalStateException("Media byte upload kept being stopped"))
            return Result.success()
        }

        val bytesToUpload = encryptedFile ?: clearFile
        if (!bytesToUpload.exists()) {
            Timber.e("## Reserved media ${params.contentUri} has no local bytes left, cannot upload it")
            notifyFailure(params, IllegalStateException("Media bytes are no longer available"))
            return Result.success()
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
                            params.localEchoIds.forEach {
                                contentUploadStateTracker.setProgress(it.eventId, current, total)
                            }
                        }
                    },
            )
            onUploadSettled(params, clearFile, encryptedFile)
            params.localEchoIds.forEach { contentUploadStateTracker.setSuccess(it.eventId) }
            Result.success()
        } catch (failure: CancellationException) {
            // Never treat a stop mid-PUT as an upload error; the files stay put for the next run.
            throw failure
        } catch (failure: Throwable) {
            // The server already has these bytes; nothing more to do.
            if (failure is Failure.ServerError && failure.error.code == MatrixError.M_CANNOT_OVERWRITE_MEDIA) {
                onUploadSettled(params, clearFile, encryptedFile)
                params.localEchoIds.forEach { contentUploadStateTracker.setSuccess(it.eventId) }
                return Result.success()
            }
            if (failure.shouldBeRetried() && runAttemptCount < MAX_ATTEMPTS) {
                Timber.w(failure, "## Media byte upload failed, will retry (attempt $runAttemptCount)")
                Result.retry()
            } else {
                // Giving up on the server copy, but the sender's own timeline should still have the
                // media, so cache it before dropping the temporary files.
                Timber.e(failure, "## Media byte upload permanently failed for ${params.contentUri}")
                onUploadSettled(params, clearFile, encryptedFile)
                notifyFailure(params, failure)
                Result.success()
            }
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
    }

    companion object {
        /**
         * One unique work name per reserved content URI, never a shared chain: a cancelled or failed
         * entry in a WorkManager chain takes everything appended after it down too, which here would
         * strand the media of attachments whose events have already been sent.
         */
        fun workName(sessionId: String, contentUri: String) = "MEDIA_BYTES_${sessionId}_$contentUri"

        private const val MAX_ATTEMPTS = 5
    }
}
