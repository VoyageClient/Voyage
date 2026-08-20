/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.internal.session.room.send.LocalEchoIdentifiers
import org.matrix.android.sdk.internal.worker.SessionWorkerParams

@JsonClass(generateAdapter = true)
internal data class UploadContentWorkerParams(
        override val sessionId: String,
        val localEchoIds: List<LocalEchoIdentifiers>,
        val attachment: ContentAttachmentData,
        val isEncrypted: Boolean,
        val compressBeforeSending: Boolean,
        /** When set, this upload fills item N of an MSC4274 gallery echo instead of the whole content. */
        val galleryItemIndex: Int? = null,
        /** Declared byte sizes of every gallery item, for one size-weighted progress bar. */
        val galleryItemSizes: List<Long>? = null,
        override val lastFailureMessage: String? = null
) : SessionWorkerParams {

    override fun withFailure(message: String) = copy(lastFailureMessage = lastFailureMessage ?: message)
}
