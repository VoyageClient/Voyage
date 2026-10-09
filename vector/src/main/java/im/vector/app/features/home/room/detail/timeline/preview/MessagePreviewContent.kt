/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.preview

import im.vector.app.core.ui.views.ForwardFrame
import im.vector.app.features.home.room.detail.timeline.item.MediaPillPreview
import im.vector.app.features.home.room.detail.timeline.item.MessageGalleryItem
import im.vector.app.features.home.room.detail.timeline.item.PollOptionViewState
import im.vector.app.features.html.BodySegment
import im.vector.app.features.media.ImageContentRenderer
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.util.MatrixItem

/**
 * A message as the long-press sheet, the reply header and the composer preview all show it. Whether its
 * media is hidden is left out: a reveal changes that without changing the event.
 */
data class MessagePreviewContent(
        /** The event as shown, restored from a redaction when one is revealed. */
        val event: TimelineEvent,
        /** Empty for uncaptioned media, null for a poll. Forward frame, emote and markers already applied. */
        val text: EpoxyCharSequence?,
        /** What stands in for [text] under hidden media that drops its caption: a forward's footer, or nothing. */
        val captionlessText: EpoxyCharSequence?,
        val textStyle: TextStyle,
        /** False for notice text, which the timeline never linkifies. */
        val linkifiable: Boolean,
        /** A table or code block, drawn in place of [text]. */
        val richSegments: List<BodySegment>?,
        /** The "(edited)" and translation markers under [richSegments], which [text] carries inline. */
        val richFooter: EpoxyCharSequence?,
        val media: Media?,
        /** A forward's header and bar around [media]; [text] then carries the rest of the frame. */
        val forwardFrame: ForwardFrame?,
        /** Null for files, whose pill already shows it. */
        val attachmentName: String?,
        /** An attachment's dimensions, duration and size. */
        val details: String?,
        /** The whole poll, drawn in place of [text]. */
        val poll: Poll?,
        /** The location's map, drawn in place of [text]; null where no static map can be drawn (below Lollipop). */
        val location: Location?,
) {
    enum class TextStyle { NORMAL, NOTICE, REDACTED }

    data class Location(
            val mapUrl: String,
            /** Whose avatar the pin carries; a plain pin for a location that isn't the sender's own. */
            val pinUser: MatrixItem?,
    )

    data class Poll(
            val question: EpoxyCharSequence,
            val options: List<PollOptionViewState>,
            val votesStatus: String?,
            val ended: Boolean,
    )

    sealed interface Media {
        data class Image(val data: ImageContentRenderer.Data, val isSticker: Boolean) : Media
        data class Gallery(val tiles: List<MessageGalleryItem.TileData>, val maxWidth: Int) : Media
        data class Pill(val pill: MediaPillPreview) : Media
    }
}
