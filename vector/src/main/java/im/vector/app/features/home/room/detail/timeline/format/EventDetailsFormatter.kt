/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.format

import android.content.Context
import im.vector.app.core.extensions.getVectorLastMessageContent
import im.vector.app.core.utils.TextUtils
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.events.model.isPollEnd
import org.matrix.android.sdk.api.session.events.model.isPollStart
import org.matrix.android.sdk.api.session.events.model.isRedacted
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageFileContent
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.threeten.bp.Duration
import javax.inject.Inject

class EventDetailsFormatter @Inject constructor(
        private val context: Context
) {

    /**
     * For an MSC4274 gallery, [galleryItemIndex] scopes the details to that item.
     */
    fun format(timelineEvent: TimelineEvent?, galleryItemIndex: Int? = null): CharSequence? {
        val event = timelineEvent?.root ?: return null

        if (event.isRedacted()) {
            return null
        }

        if (event.isEncrypted() && event.mxDecryptionResult == null) {
            return null
        }

        // What the message holds now — an edit may have put different media in it.
        val content = timelineEvent.getVectorLastMessageContent()

        if (galleryItemIndex != null && content is MessageGalleryContent) {
            return formatForAttachment(content.galleryItems().getOrNull(galleryItemIndex))
        }

        return when {
            content is MessageWithAttachmentContent -> formatForAttachment(content)
            event.isPollStart() -> formatPollMessage()
            event.isPollEnd() -> formatPollEndMessage()
            else -> null
        }
    }

    private fun formatForAttachment(content: MessageContent?): CharSequence? {
        return when (content) {
            is MessageImageContent -> formatForImageContent(content)
            is MessageVideoContent -> formatForVideoContent(content)
            is MessageAudioContent -> formatForAudioContent(content)
            is MessageFileContent -> formatForFileContent(content)
            else -> null
        }
    }

    private fun formatPollMessage() = context.getString(CommonStrings.message_reply_to_poll_preview)

    private fun formatPollEndMessage() = context.getString(CommonStrings.message_reply_to_ended_poll_preview)

    private fun formatForImageContent(content: MessageImageContent): CharSequence? {
        val info = content.info ?: return null
        return listOfNotNull(
                resolution(info.width, info.height),
                info.size.takeIf { it > 0 }?.asFileSize(),
        ).joinToString(" - ").takeIf { it.isNotEmpty() }
    }

    private fun formatForVideoContent(content: MessageVideoContent): CharSequence? {
        val info = content.videoInfo ?: return null
        return listOfNotNull(
                info.duration.takeIf { it > 0 }?.asDuration(),
                resolution(info.width, info.height),
                info.size.takeIf { it > 0 }?.asFileSize(),
        ).joinToString(" - ").takeIf { it.isNotEmpty() }
    }

    private fun formatForAudioContent(content: MessageAudioContent): CharSequence? {
        val audioInfo = content.audioInfo ?: return null
        // Voice messages show their duration on the pill, so only the file size remains here.
        val duration = audioInfo.duration?.asDuration().takeIf { content.voiceMessageIndicator == null }
        return listOfNotNull(duration, audioInfo.size?.asFileSize())
                .joinToString(" - ")
                .takeIf { it.isNotEmpty() }
    }

    private fun formatForFileContent(content: MessageFileContent): CharSequence? {
        val info = content.info ?: return null
        return listOfNotNull(
                info.size.takeIf { it > 0 }?.asFileSize(),
                info.mimeType?.takeIf { it.isNotBlank() },
        ).joinToString(" - ").takeIf { it.isNotEmpty() }
    }

    private fun resolution(width: Int, height: Int): String? =
            if (width > 0 && height > 0) "$width x $height" else null

    private fun Long.asFileSize() = TextUtils.formatFileSize(context, this)
    private fun Int.asDuration() = TextUtils.formatDuration(Duration.ofMillis(toLong()))
}
