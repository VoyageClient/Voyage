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

package org.matrix.android.sdk.api.session.room.model.message

import org.matrix.android.sdk.api.session.crypto.model.EncryptedFileInfo

/**
 * Interface for message which can contains an encrypted file.
 */
interface MessageWithAttachmentContent : MessageContent {
    /**
     * Required if the file is unencrypted. The URL (typically MXC URI) to the image.
     */
    val url: String?

    /**
     * Required if the file is encrypted. Information on the encrypted file, as specified in End-to-end encryption.
     */
    val encryptedFileInfo: EncryptedFileInfo?

    val mimeType: String?

    /**
     * Optional original filename of the uploaded file (matrix MSC2530). When present, `body`
     * is treated as a user-typed caption; otherwise `body` is the filename/description.
     */
    val filename: String?
}

/**
 * Get the url of the encrypted file or of the file.
 */
fun MessageWithAttachmentContent.getFileUrl() = encryptedFileInfo?.url ?: url

/**
 * Returns the canonical filename — the explicit `filename` field if set (MSC2530), otherwise
 * the legacy `body` field which historically doubled as the filename.
 */
fun MessageWithAttachmentContent.getFileName(): String = filename?.takeIf { it.isNotBlank() } ?: body

/**
 * Returns the user-typed plain-text caption when one is present (MSC2530 style: `filename`
 * is set, so `body` is no longer the filename and is instead the caption). Null otherwise.
 */
fun MessageWithAttachmentContent.getCaption(isReply: Boolean = false): String? {
    val name = filename ?: return null
    if (body.isBlank() || body == name) return null
    // Legacy reply-fallback bodies are shaped like:
    //   > <@user:server> previewline
    //   > another preview line
    //
    //   actualFileName.png
    // After stripping that prefix the only remaining content is the filename — not a real
    // caption. Suppress it for replies when the last non-blank line equals the filename.
    if (isReply) {
        val lastLine = body.lineSequence()
                .map { it.trim() }
                .lastOrNull { it.isNotEmpty() }
        if (lastLine == name) return null
    }
    return body
}

/**
 * Returns the user-typed HTML caption when present. Null otherwise.
 */
fun MessageWithAttachmentContent.getFormattedCaption(isReply: Boolean = false): String? =
        if (getCaption(isReply) != null) (this as? MessageContentWithFormattedBody)?.matrixFormattedBody else null

/**
 * Returns the first MSC3952 mention (typically the sender of the replied-to event) when the
 * media event carries one. Used as a fallback "In reply to @user" hint while the target event
 * is still being fetched.
 */
fun MessageWithAttachmentContent.getMentionHint(): String? = when (this) {
    is MessageImageContent -> mentions?.userIds?.firstOrNull()
    is MessageVideoContent -> mentions?.userIds?.firstOrNull()
    is MessageAudioContent -> mentions?.userIds?.firstOrNull()
    is MessageFileContent -> mentions?.userIds?.firstOrNull()
    is MessageStickerContent -> mentions?.userIds?.firstOrNull()
    else -> null
}
