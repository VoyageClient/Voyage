/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import im.vector.app.core.extensions.getVectorLastMessageContent
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.content.EncryptedEventContent
import org.matrix.android.sdk.api.session.events.model.isThread
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent

/**
 * Whether the timeline draws this message as a "Debug" notice rather than its content: an edit event,
 * or a thread message seen outside its thread. Previews of it (replies, the action sheet) follow suit.
 */
fun TimelineEvent.rendersAsDebugMessage(threadsEnabled: Boolean, inThreadTimeline: Boolean): Boolean {
    if (root.isRedacted() || getVectorLastMessageContent() == null) return false
    return isEditEvent() || threadsEnabled && !inThreadTimeline && root.isThread()
}

/** An `m.replace` event itself, as opposed to the message it edits. */
fun TimelineEvent.isEditEvent(): Boolean =
        getVectorLastMessageContent()?.relatesTo?.type == RelationType.REPLACE ||
                root.isEncrypted() && root.content.toModel<EncryptedEventContent>()?.relatesTo?.type == RelationType.REPLACE
