/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import android.content.Context
import androidx.work.WorkerParameters
import org.matrix.android.sdk.internal.SessionManager
import org.matrix.android.sdk.internal.session.SessionComponent
import org.matrix.android.sdk.internal.worker.SessionSafeCoroutineWorker
import javax.inject.Inject

internal class UploadMediaBytesWorker(context: Context, params: WorkerParameters, sessionManager: SessionManager) :
        SessionSafeCoroutineWorker<UploadMediaBytesWorkerParams>(
                context, params, sessionManager, UploadMediaBytesWorkerParams::class.java
        ) {

    @Inject lateinit var uploadMediaBytesTaskBody: UploadMediaBytesTaskBody

    override fun injectWith(injector: SessionComponent) {
        injector.inject(this)
    }

    override fun body() = uploadMediaBytesTaskBody

    companion object {
        /**
         * One unique work name per reserved content URI, never a shared chain: a cancelled or failed
         * entry in a WorkManager chain takes everything appended after it down too, which here would
         * strand the media of attachments whose events have already been sent.
         */
        fun workName(sessionId: String, contentUri: String) = "MEDIA_BYTES_${sessionId}_$contentUri"
    }
}
