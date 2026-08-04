/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import com.airbnb.mvrx.MavericksState
import im.vector.app.features.attachments.editor.image.ImageEditorEdits
import org.matrix.android.sdk.api.session.content.ContentAttachmentData

/** What an edited attachment was made from, so the editor can reopen against the original. */
data class EditRecord(val originalUri: String, val edits: ImageEditorEdits)

data class AttachmentsPreviewViewState(
        val attachments: List<ContentAttachmentData>,
        val currentAttachmentIndex: Int = 0,
        val sendImagesWithOriginalSize: Boolean = false,
        /** Keyed by the attachment's current queryUri. */
        val editRecords: Map<String, EditRecord> = emptyMap()
) : MavericksState {

    constructor(args: AttachmentsPreviewArgs) : this(attachments = args.attachments)

    /**
     * Editing rewrites an attachment's queryUri. Keying the list off that would make Epoxy treat
     * the result as a different item, removing and re-inserting it, which shifts the pager onto a
     * neighbour. The original uri stays put for the lifetime of the screen.
     */
    fun stableIdOf(attachment: ContentAttachmentData): String =
            editRecords[attachment.queryUri]?.originalUri ?: attachment.queryUri
}
