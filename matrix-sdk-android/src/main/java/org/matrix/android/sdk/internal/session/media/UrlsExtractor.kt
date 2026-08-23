/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.media

import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageGalleryContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.model.message.galleryCaption
import org.matrix.android.sdk.api.session.room.model.message.getCaption
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLastMessageContent
import org.matrix.android.sdk.api.session.room.timeline.isReply
import org.matrix.android.sdk.api.util.ContentUtils
import javax.inject.Inject

internal class UrlsExtractor @Inject constructor(
        webUrlPattern: WebUrlPattern,
) {
    // Sadly Patterns.WEB_URL_WITH_PROTOCOL is not public so filter the protocol later
    private val urlRegex = webUrlPattern.regex

    fun extract(event: TimelineEvent): List<String> {
        return event.takeIf { it.root.getClearType() == EventType.MESSAGE }
                ?.getLastMessageContent()
                ?.previewableText(event.isReply())
                ?.let { extract(it) }
                .orEmpty()
    }

    fun extract(text: String): List<String> {
        return urlRegex.findAll(text)
                .map { it.value }
                .filter { it.startsWith("https://") || it.startsWith("http://") }
                .distinct()
                .toList()
    }

    companion object {
        /** The user-typed text of a message that may carry links: a text body or a media caption (MSC2530 / MSC4274). */
        fun MessageContent.previewableText(isReply: Boolean): String? {
            return when {
                msgType == MessageType.MSGTYPE_TEXT ||
                        msgType == MessageType.MSGTYPE_NOTICE ||
                        msgType == MessageType.MSGTYPE_EMOTE -> {
                    if (isReply) ContentUtils.extractUsefulTextFromReply(body) else body
                }
                this is MessageWithAttachmentContent -> getCaption(isReply)
                this is MessageGalleryContent -> galleryCaption()
                else -> null
            }
        }
    }
}
