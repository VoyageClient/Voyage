/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.render

import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.StringProvider
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.getPollQuestion
import org.matrix.android.sdk.api.session.events.model.getRelationContent
import org.matrix.android.sdk.api.session.events.model.isAudioMessage
import org.matrix.android.sdk.api.session.events.model.isReply
import org.matrix.android.sdk.api.session.events.model.isFileMessage
import org.matrix.android.sdk.api.session.events.model.isImageMessage
import org.matrix.android.sdk.api.session.events.model.isLiveLocation
import org.matrix.android.sdk.api.session.events.model.isPollEnd
import org.matrix.android.sdk.api.session.events.model.isPollStart
import org.matrix.android.sdk.api.session.events.model.isSticker
import org.matrix.android.sdk.api.session.events.model.isVideoMessage
import org.matrix.android.sdk.api.session.events.model.isVoiceMessage
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.getTimelineEvent
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessagePollContent
import org.matrix.android.sdk.api.session.room.model.relation.ReplyToContent
import org.matrix.android.sdk.api.session.room.timeline.getLastMessageContent
import org.matrix.android.sdk.api.util.ContentUtils
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

private const val MX_REPLY_OPEN = "<mx-reply>"
private const val MAX_PREVIEW_LENGTH = 120

@Singleton
class ProcessBodyOfReplyToEventUseCase @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val stringProvider: StringProvider,
) {

    // Events fetched on-demand for replies whose target isn't in the local timeline DB. Kept
    // in-process and shared across uses of this singleton so a fetched event populates the
    // preview on the next render (which will fire from Realm changes, scroll bind, etc).
    private val fetchedEvents: MutableMap<String, Event> = Collections.synchronizedMap(HashMap())
    private val inflightFetches: MutableSet<String> = Collections.synchronizedSet(HashSet())
    // Events whose fetch failed (404, network error, etc). We don't retry for the lifetime
    // of the process — without this, every render would re-launch a doomed fetch and the
    // resulting churn could keep the unresolved-reply block in a permanent half-fetched
    // limbo. Cleared on app restart so transient network issues recover.
    private val failedFetches: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _resolvedReplyTargets = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /**
     * Emits each event id whose on-demand fetch just completed (success or failure). Timeline
     * UI subscribes and asks the controller to invalidate the matching positions so the next
     * model build either swaps in the real preview (success) or settles on the unresolved
     * message (failure), without waiting for room re-open / external invalidation.
     */
    val resolvedReplyTargets: SharedFlow<String> = _resolvedReplyTargets.asSharedFlow()

    fun execute(
            roomId: String,
            matrixFormattedBody: String,
            replyToContent: ReplyToContent,
            mentionedUserHint: String? = null,
    ): String {
        val eventId = replyToContent.eventId ?: return matrixFormattedBody
        // Look in the timeline DB first (decorated, full TimelineEventEntity); fall back to
        // the general event cache (populated by ensureEventCached across app restarts); fall
        // back to anything fetched in this session that hasn't landed in Realm yet.
        val repliedToEvent = getEvent(eventId, roomId)
                ?: activeSessionHolder.getSafeActiveSession()?.eventService()?.getEventFromCache(roomId, eventId)
                ?: fetchedEvents[eventId]

        if (repliedToEvent == null && !failedFetches.contains(eventId)) {
            triggerFetch(roomId, eventId)
        }

        // Always rebuild the <mx-reply> block from our own data instead of trying to localize
        // the sender's pre-rendered fallback. This makes the rendered output a function of the
        // cached event state, so when the on-demand fetch lands and our cache is populated,
        // the next render produces fresh HTML (with real sender + preview) and the UI updates
        // without needing to back out of the room.
        val bodyWithoutReply = stripExistingMxReply(matrixFormattedBody)
        return buildSyntheticReplyBlock(roomId, eventId, repliedToEvent, mentionedUserHint) + bodyWithoutReply
    }

    private fun stripExistingMxReply(body: String): String {
        val start = body.indexOf(MX_REPLY_OPEN, ignoreCase = true)
        if (start == -1) return body
        val endTag = "</mx-reply>"
        val end = body.indexOf(endTag, startIndex = start, ignoreCase = true)
        if (end == -1) return body
        return body.substring(0, start) + body.substring(end + endTag.length)
    }

    private fun buildSyntheticReplyBlock(
            roomId: String,
            eventId: String,
            repliedToEvent: Event?,
            mentionedUserHint: String?,
    ): String {
        // matrix.to expects raw mxid / event id (`!room:server` / `$event:server`) — those
        // chars are valid in URL paths and the permalink parser matches the raw form.
        val eventPermalink = "https://matrix.to/#/$roomId/$eventId"
        val replyPrefix = escapeHtml(stringProvider.getString(CommonStrings.message_reply_to_prefix))

        if (repliedToEvent != null) {
            // Loaded — full matrix-spec format with the actual sender + preview.
            val senderId = repliedToEvent.senderId
            val senderPermalink = senderId?.let { "https://matrix.to/#/$it" }
            val preview = escapeHtml(repliedToEvent.shortPreview().orEmpty())
            val senderAnchor = if (senderId != null && senderPermalink != null) {
                " <a href=\"$senderPermalink\">${escapeHtml(senderId)}</a>"
            } else {
                ""
            }
            return "<mx-reply><blockquote>" +
                    "<a href=\"$eventPermalink\">$replyPrefix</a>$senderAnchor" +
                    "<br />$preview" +
                    "</blockquote></mx-reply>"
        }

        // Not loaded — covers both "fetch still in flight" and "fetch failed". Display the
        // unresolved-event explanation. If we have an MSC3952 mention hint, prepend an
        // "In reply to @hint" line so the user at least sees who was replied to.
        val unresolvedText = escapeHtml(stringProvider.getString(CommonStrings.message_reply_to_unresolved))
        return if (mentionedUserHint != null) {
            val senderPermalink = "https://matrix.to/#/$mentionedUserHint"
            "<mx-reply><blockquote>" +
                    "<a href=\"$eventPermalink\">$replyPrefix</a> " +
                    "<a href=\"$senderPermalink\">${escapeHtml(mentionedUserHint)}</a>" +
                    "<br />$unresolvedText" +
                    "</blockquote></mx-reply>"
        } else {
            "<mx-reply><blockquote>$unresolvedText</blockquote></mx-reply>"
        }
    }

    private fun Event.shortPreview(): String? {
        // Mirror the per-message-type strings the old code used so the inline preview reads
        // the same as before for media / polls / live location replies.
        return when {
            isFileMessage() -> stringProvider.getString(CommonStrings.message_reply_to_sender_sent_file)
            isVoiceMessage() -> stringProvider.getString(CommonStrings.message_reply_to_sender_sent_voice_message)
            isAudioMessage() -> stringProvider.getString(CommonStrings.message_reply_to_sender_sent_audio_file)
            isImageMessage() -> stringProvider.getString(CommonStrings.message_reply_to_sender_sent_image)
            isVideoMessage() -> stringProvider.getString(CommonStrings.message_reply_to_sender_sent_video)
            isSticker() -> stringProvider.getString(CommonStrings.message_reply_to_sender_sent_sticker)
            isLiveLocation() -> stringProvider.getString(CommonStrings.live_location_description)
            isPollEnd() -> getPollQuestionFromPollEnd(this)
                    ?: stringProvider.getString(CommonStrings.message_reply_to_sender_ended_poll)
            isPollStart() -> getPollQuestion()
                    ?: stringProvider.getString(CommonStrings.message_reply_to_sender_created_poll)
            else -> getClearContent().toModel<MessageContent>()?.body?.let { rawBody ->
                // Strip legacy "> <@user:server> previewline\n\n" reply prefix so the inline
                // preview of a replied-to text reply doesn't include the quoted ancestor body.
                val body = if (isReply()) ContentUtils.extractUsefulTextFromReply(rawBody) else rawBody
                if (body.length > MAX_PREVIEW_LENGTH) body.substring(0, MAX_PREVIEW_LENGTH) + "…" else body
            }
        }
    }

    private fun escapeHtml(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun triggerFetch(roomId: String, eventId: String) {
        // Don't retry a fetch we already know has failed for this process lifetime.
        if (failedFetches.contains(eventId)) return
        if (!inflightFetches.add(eventId)) return
        val session = activeSessionHolder.getSafeActiveSession() ?: run {
            inflightFetches.remove(eventId)
            return
        }
        fetchScope.launch {
            var succeeded = false
            try {
                // ensureEventCached persists to the local EventEntity cache so the next app
                // start doesn't need to re-fetch.
                val event = session.eventService().ensureEventCached(roomId, eventId)
                if (event != null) {
                    fetchedEvents[eventId] = event
                    succeeded = true
                }
            } catch (_: Throwable) {
                // Treated as a fetch failure below.
            } finally {
                if (!succeeded) {
                    failedFetches.add(eventId)
                }
                inflightFetches.remove(eventId)
                // Notify regardless of outcome — the UI needs to refresh either way: on
                // success to swap in the real preview, on failure to settle the unresolved
                // message text.
                _resolvedReplyTargets.tryEmit(eventId)
            }
        }
    }

    private fun getEvent(eventId: String, roomId: String) =
            getTimelineEvent(eventId, roomId)
                    ?.root

    private fun getTimelineEvent(eventId: String, roomId: String) =
            activeSessionHolder.getSafeActiveSession()
                    ?.getRoom(roomId)
                    ?.getTimelineEvent(eventId)

    private fun getPollQuestionFromPollEnd(event: Event): String? {
        val eventId = event.getRelationContent()?.eventId.orEmpty()
        val roomId = event.roomId.orEmpty()
        return if (eventId.isEmpty() || roomId.isEmpty()) {
            null
        } else {
            (getTimelineEvent(eventId, roomId)
                    ?.getLastMessageContent() as? MessagePollContent)
                    ?.getBestPollCreationInfo()
                    ?.question
                    ?.getBestQuestion()
        }
    }
}
