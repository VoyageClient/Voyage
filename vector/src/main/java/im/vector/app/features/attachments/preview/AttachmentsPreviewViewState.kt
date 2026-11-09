/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.attachments.preview

import com.airbnb.mvrx.MavericksState
import im.vector.app.features.attachments.editor.AttachmentEdits
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import org.matrix.android.sdk.api.util.MimeTypes

/**
 * What an edited attachment was made from, so the editor can reopen against the original — and so
 * undoing every edit can put the original attachment back, metadata and all.
 */
data class EditRecord(
        val original: ContentAttachmentData,
        val edits: AttachmentEdits,
        val sourceRasterSize: Pair<Int, Int>? = null,
) {
    val originalUri: String get() = original.queryUri
}

data class AttachmentsPreviewViewState(
        val attachments: List<ContentAttachmentData>,
        val currentAttachmentIndex: Int = 0,
        /** Keyed by [stableIdOf]: sending untouched is a choice per attachment, not per send. */
        val keepOriginalSize: Set<String> = emptySet(),
        /** The fragment asks on every view creation; only the first may seed [keepOriginalSize]. */
        val compressionDefaultApplied: Boolean = false,
        val sendAsSpoiler: Set<String> = emptySet(),
        val rasterize: Set<String> = emptySet(),
        /** Keyed by the attachment's current queryUri. */
        val editRecords: Map<String, EditRecord> = emptyMap(),
        /** Keyed by [stableIdOf], so editing an attachment does not lose its compression choice. */
        val compressionSettings: Map<String, CompressionSettings> = emptyMap(),
        /**
         * Keyed by [stableIdOf]. Attachments sent as one gallery event share a single caption — the
         * gallery has one body — so there the same text is held against every attachment.
         */
        val captions: Map<String, String> = emptyMap(),
        val sharesOneCaption: Boolean = false,
        /** Content that replaced an attachment outright (a still taken from a video) keeps its id. */
        val stableIdAliases: Map<String, String> = emptyMap(),
) : MavericksState {

    constructor(args: AttachmentsPreviewArgs) : this(
            attachments = args.attachments,
            // The last one carries it, so the caption reads below the whole batch once sent.
            captions = args.attachments.lastOrNull()
                    ?.let { mapOf(it.queryUri to args.caption.orEmpty()) }
                    .orEmpty(),
    )

    fun originalOf(attachment: ContentAttachmentData): ContentAttachmentData = editRecords[attachment.queryUri]?.original ?: attachment

    fun isSvg(attachment: ContentAttachmentData): Boolean = originalOf(attachment).let {
        it.type == ContentAttachmentData.Type.IMAGE && it.getSafeMimeType() == MimeTypes.Svg
    }

    fun previewOf(attachment: ContentAttachmentData): ContentAttachmentData =
            if (isSvg(attachment) && !rasterize.contains(stableIdOf(attachment))) originalOf(attachment) else attachment

    fun canSendOriginal(attachment: ContentAttachmentData): Boolean = attachment.isCompressible() && !isSvg(attachment)

    // An edit can turn an item into something uncompressible (audio taken from a video) under the same id.
    fun isSentOriginal(attachment: ContentAttachmentData): Boolean = canSendOriginal(attachment) && keepOriginalSize.contains(stableIdOf(attachment))

    /** [preview] (from [previewOf]) with the choices made on this screen written into its compression fields. */
    fun outgoingOf(preview: ContentAttachmentData, rasterizing: Boolean): ContentAttachmentData {
        if (preview.getSafeMimeType() == MimeTypes.Svg && !rasterizing) {
            return preview.copy(compressionQuality = null, compressionWidth = null, compressionHeight = null)
        }
        // Anything non-null in the compression fields would re-encode (and, for video, transcode) it.
        if (isSentOriginal(preview)) {
            return preview.copy(keepOriginalSize = true, compressionQuality = null, compressionWidth = null, compressionHeight = null)
        }
        // Boxes left at the source size are not a resize request, so they must not make the
        // attachment look custom-compressed.
        val sourceWidth = preview.displayWidth?.toInt()
        val sourceHeight = preview.displayHeight?.toInt()
        val settings = compressionSettings[stableIdOf(preview)]
                ?.let { if (!isSvg(preview) && sourceWidth != null && sourceHeight != null) it.withoutRedundantSize(sourceWidth, sourceHeight) else it }
                ?: return preview
        return preview.copy(
                compressionQuality = settings.quality.takeIf { it != CompressionSettings.STANDARD_QUALITY },
                compressionWidth = settings.width,
                compressionHeight = settings.height,
        )
    }

    fun captionOf(attachment: ContentAttachmentData): String = captions[stableIdOf(attachment)].orEmpty()

    /**
     * Editing rewrites an attachment's queryUri. Keying the list off that would make Epoxy treat
     * the result as a different item, removing and re-inserting it, which shifts the pager onto a
     * neighbour. The original uri stays put for the lifetime of the screen.
     */
    fun stableIdOf(attachment: ContentAttachmentData): String {
        val uri = editRecords[attachment.queryUri]?.originalUri ?: attachment.queryUri
        return stableIdAliases[uri] ?: uri
    }
}
