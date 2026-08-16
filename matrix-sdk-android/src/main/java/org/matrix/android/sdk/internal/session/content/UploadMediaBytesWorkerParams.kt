/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.content

import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.internal.session.room.send.LocalEchoIdentifiers
import org.matrix.android.sdk.internal.worker.SessionWorkerParams

@JsonClass(generateAdapter = true)
internal data class UploadMediaBytesWorkerParams(
        override val sessionId: String,
        val localEchoIds: List<LocalEchoIdentifiers>,
        val contentUri: String,
        val filename: String?,
        val mimeType: String?,
        val isEncrypted: Boolean,
        /** The bytes as the recipient will see them once decrypted; also what the cache serves. */
        val clearFilePath: String,
        /** The ciphertext actually uploaded, for an encrypted room. */
        val encryptedFilePath: String?,
        /** Which MSC4274 gallery item these bytes belong to, with every item's declared size. */
        val galleryItemIndex: Int? = null,
        val galleryItemSizes: List<Long>? = null,
        override val lastFailureMessage: String? = null
) : SessionWorkerParams
