/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.format

import android.content.Context
import im.vector.app.core.utils.TextUtils
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.isAudioMessage
import org.matrix.android.sdk.api.session.events.model.isFileMessage
import org.matrix.android.sdk.api.session.events.model.isImageMessage
import org.matrix.android.sdk.api.session.events.model.isPollEnd
import org.matrix.android.sdk.api.session.events.model.isPollStart
import org.matrix.android.sdk.api.session.events.model.isVideoMessage
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageAudioContent
import org.matrix.android.sdk.api.session.room.model.message.MessageFileContent
import org.matrix.android.sdk.api.session.room.model.message.MessageImageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageVideoContent
import org.threeten.bp.Duration
import javax.inject.Inject

class EventDetailsFormatter @Inject constructor(
        private val context: Context
) {

    fun format(event: Event?): CharSequence? {
        event ?: return null

        if (event.isRedacted()) {
            return null
        }

        if (event.isEncrypted() && event.mxDecryptionResult == null) {
            return null
        }

        return when {
            event.isImageMessage() -> formatForImageMessage(event)
            event.isVideoMessage() -> formatForVideoMessage(event)
            event.isAudioMessage() -> formatForAudioMessage(event)
            event.isFileMessage() -> formatForFileMessage(event)
            event.isPollStart() -> formatPollMessage()
            event.isPollEnd() -> formatPollEndMessage()
            else -> null
        }
    }

    private fun formatPollMessage() = context.getString(CommonStrings.message_reply_to_poll_preview)

    private fun formatPollEndMessage() = context.getString(CommonStrings.message_reply_to_ended_poll_preview)

    private fun formatForImageMessage(event: Event): CharSequence? {
        val info = event.getClearContent().toModel<MessageImageContent>()?.info ?: return null
        return listOfNotNull(
                resolution(info.width, info.height),
                info.size.takeIf { it > 0 }?.asFileSize(),
        ).joinToString(" - ").takeIf { it.isNotEmpty() }
    }

    private fun formatForVideoMessage(event: Event): CharSequence? {
        val info = event.getClearContent().toModel<MessageVideoContent>()?.videoInfo ?: return null
        return listOfNotNull(
                info.duration.takeIf { it > 0 }?.asDuration(),
                resolution(info.width, info.height),
                info.size.takeIf { it > 0 }?.asFileSize(),
        ).joinToString(" - ").takeIf { it.isNotEmpty() }
    }

    private fun formatForAudioMessage(event: Event): CharSequence? {
        val content = event.getClearContent().toModel<MessageAudioContent>() ?: return null
        val audioInfo = content.audioInfo ?: return null
        // Voice messages show their duration on the pill, so only the file size remains here.
        val duration = audioInfo.duration?.asDuration().takeIf { content.voiceMessageIndicator == null }
        return listOfNotNull(duration, audioInfo.size?.asFileSize())
                .joinToString(" - ")
                .takeIf { it.isNotEmpty() }
    }

    private fun formatForFileMessage(event: Event): CharSequence? {
        val info = event.getClearContent().toModel<MessageFileContent>()?.info ?: return null
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
