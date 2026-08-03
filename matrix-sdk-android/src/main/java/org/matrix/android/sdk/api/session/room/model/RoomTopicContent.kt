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

package org.matrix.android.sdk.api.session.room.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.util.MimeTypes

/**
 * Class representing the EventType.STATE_ROOM_TOPIC state event content.
 *
 * `topic` is the legacy plain-text field. The `m.topic` block (MSC3765) carries multiple mimetype
 * representations, letting clients ship an HTML rendering alongside the plain text — the same split as
 * a message's body / formatted_body. The stable `m.topic` id is written; the pre-stabilization unstable
 * id is still accepted on read for topics set by older clients.
 */
@JsonClass(generateAdapter = true)
data class RoomTopicContent(
        @Json(name = "topic") val topic: String? = null,
        @Json(name = TOPIC_MSC3765) val extensibleTopicStable: TopicContent? = null,
        @Json(name = TOPIC_MSC3765_UNSTABLE) val extensibleTopicUnstable: TopicContent? = null,
) {

    /** The HTML rendering of the topic, or null when only plain text is available. */
    fun getBestFormattedTopic(): String? = extensibleTopic()?.textRepresentations
            ?.firstOrNull { it.mimeType == MimeTypes.Html }
            ?.body
            ?.takeIf { it.isNotEmpty() }

    /**
     * The plain-text topic. MSC3765 says to render the first representation whose mimetype is
     * understood; a missing mimetype defaults to text/plain (MSC1767). Falls back to the legacy field.
     */
    fun getBestTopic(): String? = extensibleTopic()?.textRepresentations
            ?.firstOrNull { it.mimeType == null || it.mimeType == MimeTypes.PlainText }
            ?.body
            ?: topic

    private fun extensibleTopic(): TopicContent? = extensibleTopicStable ?: extensibleTopicUnstable

    companion object {
        const val TOPIC_MSC3765 = "m.topic"
        const val TOPIC_MSC3765_UNSTABLE = "org.matrix.msc3765.topic"
    }
}

@JsonClass(generateAdapter = true)
data class TopicContent(
        @Json(name = "m.text") val textRepresentations: List<TopicRepresentation>? = null
)

@JsonClass(generateAdapter = true)
data class TopicRepresentation(
        @Json(name = "body") val body: String,
        @Json(name = "mimetype") val mimeType: String? = null
)
