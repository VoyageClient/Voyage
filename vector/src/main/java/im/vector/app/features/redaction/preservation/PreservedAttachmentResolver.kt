/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.redaction.preservation

import dagger.Lazy
import im.vector.app.core.di.ActiveSessionHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.isRedacted
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.getTimelineEvent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.uploads.UploadEvent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The media of redacted messages, for the screens that list a room's attachments.
 *
 * Those screens read the SDK's own tables, where a redaction has already stripped the event, so a
 * message the timeline is happily showing again is invisible to them — the viewer opens it alone
 * ("1 of 1") and the uploads panel omits it. This rebuilds the missing entries from the preserved
 * copies, limited to the ones actually revealed.
 */
@Singleton
class PreservedAttachmentResolver @Inject constructor(
        // Lazy: ActiveSessionHolder reaches this class through ConfigureAndStartSessionUseCase.
        private val activeSessionHolder: Lazy<ActiveSessionHolder>,
        private val revealManager: RedactedContentRevealManager,
        private val mediaStore: PreservedMediaStore,
) {

    fun fileFor(roomId: String, eventId: String): File? = mediaStore.fileFor(roomId, eventId).takeIf { it.isFile }

    /** Restored attachment events for [roomId], newest first. */
    suspend fun attachments(roomId: String): List<TimelineEvent> {
        return revealedAttachments(roomId).map { (event, sender) ->
            TimelineEvent(
                    root = event,
                    localId = event.eventId.orEmpty().hashCode().toLong(),
                    eventId = event.eventId.orEmpty(),
                    displayIndex = 0,
                    senderInfo = sender,
            )
        }
    }

    /** The same set shaped for the uploads panel. */
    suspend fun uploads(roomId: String): List<UploadEvent> {
        return revealedAttachments(roomId).mapNotNull { (event, sender) ->
            val content = event.content.toModel<MessageContent>() as? MessageWithAttachmentContent ?: return@mapNotNull null
            UploadEvent(
                    root = event,
                    eventId = event.eventId.orEmpty(),
                    contentWithAttachmentContent = content,
                    senderInfo = sender,
            )
        }
    }

    // withContext, not a caller contract: this reads preferences and runs a member query per preserved
    // event, and one of the two callers invokes it straight from a Mavericks viewModelScope (main).
    private suspend fun revealedAttachments(roomId: String): List<Pair<Event, SenderInfo>> = withContext(Dispatchers.IO) {
        val session = activeSessionHolder.get().getSafeActiveSession() ?: return@withContext emptyList()
        val room = session.getRoom(roomId)
        session.redactedContentService().getPreservedContentInRoom(roomId)
                // A preserved row can describe an event the timeline still has live (a tombstone for a
                // cancelled upload), and that one is already in the caller's list. Re-adding it would
                // double the attachment, and Epoxy rejects the duplicate id outright.
                .filter { preserved ->
                    val local = room?.getTimelineEvent(preserved.eventId)
                    local == null || local.root.isRedacted()
                }
                // Not gated on a local media file: MSC2815 restores the content but never the media, and
                // the server usually still serves the attachment. The renderers prefer the local file
                // when there is one and fall back to the mxc url when there isn't.
                .filter { preserved ->
                    revealManager.isRevealed(roomId, preserved.eventId, preserved.senderId == session.myUserId)
                }
                .mapNotNull { preserved ->
                    // Only real attachments: the preservation store also holds plain text messages.
                    // MessageContent, not MessageWithAttachmentContent: only the former has a Moshi
                    // adapter (polymorphic on msgtype), the latter is a bare interface and never parses.
                    preserved.content.toModel<MessageContent>() as? MessageWithAttachmentContent ?: return@mapNotNull null
                    val event = Event(
                            type = preserved.clearType?.takeIf { it.isNotEmpty() } ?: EventType.MESSAGE,
                            eventId = preserved.eventId,
                            content = preserved.content,
                            originServerTs = preserved.originServerTs,
                            senderId = preserved.senderId,
                            roomId = roomId,
                    )
                    val member = preserved.senderId?.let { room?.membershipService()?.getRoomMember(it) }
                    event to SenderInfo(
                            userId = preserved.senderId.orEmpty(),
                            displayName = member?.displayName,
                            isUniqueDisplayName = true,
                            avatarUrl = member?.avatarUrl,
                    )
                }
    }
}
