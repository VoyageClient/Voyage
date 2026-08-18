/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.share

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import java.util.UUID

sealed class SharedData : Parcelable {

    @Parcelize
    data class Text(val text: String) : SharedData()

    @Parcelize
    data class Attachments(
            val attachmentData: List<ContentAttachmentData>,
            /** One caption per attachment, in the same order; empty until the previewer has been through. */
            val captions: List<String> = emptyList(),
    ) : SharedData()

    @Parcelize
    data class Forward(val eventType: String, val payloadId: String) : SharedData()
}

// Content is held in-process keyed by payloadId rather than parcelled, so the original
// Map<String, Any?> reaches the send pipeline untouched.
object ForwardPayloadHolder {
    private val store = mutableMapOf<String, Map<String, Any?>>()

    fun put(content: Map<String, Any?>): String {
        val id = UUID.randomUUID().toString()
        store[id] = content
        return id
    }

    fun take(id: String): Map<String, Any?>? = store.remove(id)
}
