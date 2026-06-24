/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.render

import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.resources.StringProvider
import im.vector.app.features.home.room.detail.timeline.format.NoticeEventFormatter
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
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
import org.matrix.android.sdk.api.session.room.model.message.MessageContentWithFormattedBody
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.api.session.room.model.message.MessagePollContent
import org.matrix.android.sdk.api.session.room.model.relation.ReplyToContent
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.getLastMessageContent
import org.matrix.android.sdk.api.util.ContentUtils
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

private const val MX_REPLY_OPEN = "<mx-reply>"
private const val MAX_PREVIEW_LENGTH = 240
private const val MAX_ENTITY_LENGTH = 12
private const val DECRYPTION_WATCH_ATTEMPTS = 20
private const val DECRYPTION_WATCH_INTERVAL_MS = 300L
private val VOID_HTML_TAGS = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr"
)

@Singleton
class ProcessBodyOfReplyToEventUseCase @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
        private val stringProvider: StringProvider,
        private val noticeEventFormatter: NoticeEventFormatter,
        private val colorProvider: ColorProvider,
) {

    // Theme's muted/notice colour as a #AARRGGBB string for the <font> reply previews, so they
    // match the notice grey used elsewhere instead of a hardcoded "gray" that's wrong per theme.
    // Keep the alpha: SC themes express the muted colour as translucent white (#b3ffffff); dropping
    // the alpha would collapse it to pure white and the preview would lose its muted look.
    private fun noticeColorHex(): String {
        val color = colorProvider.getColorFromAttribute(im.vector.lib.ui.styles.R.attr.vctr_content_secondary)
        return String.format("#%08X", color)
    }

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
    // Reply targets we're polling for decryption to avoid launching duplicate watchers.
    private val decryptionWatches: MutableSet<String> = Collections.synchronizedSet(HashSet())
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
        // Prefer the full TimelineEvent so we can resolve the latest edited content for the
        // preview. Falls back to the bare Event from the cross-room event cache, then to any
        // on-demand fetch result.
        val timelineEvent = getTimelineEvent(eventId, roomId)
        val repliedToEvent = timelineEvent?.root
                ?: activeSessionHolder.getSafeActiveSession()?.eventService()?.getEventFromCache(roomId, eventId)
                ?: fetchedEvents[eventId]
        val latestContent = timelineEvent?.getLastMessageContent()

        if (repliedToEvent == null && !failedFetches.contains(eventId)) {
            triggerFetch(roomId, eventId)
        } else if (repliedToEvent?.getClearType() == EventType.ENCRYPTED) {
            // Resolved from the local DB but not decrypted yet (the live timeline may already
            // show it decrypted — its decryption result just hasn't landed in our snapshot).
            // Poll until it decrypts, then ask the UI to rebuild so the preview fills in.
            watchForDecryption(roomId, eventId)
        }

        // Always rebuild the <mx-reply> block from our own data instead of trying to localize
        // the sender's pre-rendered fallback. This makes the rendered output a function of the
        // cached event state, so when the on-demand fetch lands and our cache is populated,
        // the next render produces fresh HTML (with real sender + preview) and the UI updates
        // without needing to back out of the room.
        val bodyWithoutReply = stripExistingMxReply(matrixFormattedBody)
        return buildSyntheticReplyBlock(roomId, eventId, repliedToEvent, timelineEvent, latestContent, mentionedUserHint) + bodyWithoutReply
    }

    /**
     * Strip any embedded `<mx-reply>...</mx-reply>` block from a formatted body so the caller
     * can render the bare body without legacy reply fallback markup. Modern replies sent by
     * this client no longer embed mx-reply, but messages from legacy senders still do.
     */
    fun stripExistingMxReply(body: String): String {
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
            repliedToTimelineEvent: TimelineEvent?,
            latestContent: MessageContent?,
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
            val preview = repliedToEvent.shortPreviewHtml(latestContent, repliedToTimelineEvent, roomId).orEmpty()
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
        val unresolvedText = escapeHtml(stringProvider.getString(CommonStrings.in_reply_to_error))
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

    /**
     * Returns the inline preview as ready-to-embed HTML. For text messages we prefer
     * `formatted_body` (with any embedded `<mx-reply>` stripped) so the preview shows the
     * sender's actual formatting — links, inline code, bold, etc. — instead of the raw
     * markdown source. For media / polls / live location we emit a localized stub.
     */
    private fun Event.shortPreviewHtml(latestContent: MessageContent?, timelineEvent: TimelineEvent?, roomId: String): String? {
        return when {
            // Redacted (deleted) target: show the muted "Message deleted" notice.
            isRedacted() ->
                "<font color=\"${noticeColorHex()}\">" + escapeHtml(noticeEventFormatter.formatRedactedEvent(this)) + "</font>"
            isFileMessage() -> escapeHtml(stringProvider.getString(CommonStrings.message_reply_to_sender_sent_file))
            isVoiceMessage() -> escapeHtml(stringProvider.getString(CommonStrings.message_reply_to_sender_sent_voice_message))
            isAudioMessage() -> escapeHtml(stringProvider.getString(CommonStrings.message_reply_to_sender_sent_audio_file))
            isImageMessage() -> escapeHtml(stringProvider.getString(CommonStrings.message_reply_to_sender_sent_image))
            isVideoMessage() -> escapeHtml(stringProvider.getString(CommonStrings.message_reply_to_sender_sent_video))
            isSticker() -> escapeHtml(stringProvider.getString(CommonStrings.message_reply_to_sender_sent_sticker))
            isLiveLocation() -> escapeHtml(stringProvider.getString(CommonStrings.live_location_description))
            isPollEnd() -> escapeHtml(
                    getPollQuestionFromPollEnd(this) ?: stringProvider.getString(CommonStrings.message_reply_to_sender_ended_poll)
            )
            isPollStart() -> escapeHtml(
                    getPollQuestion() ?: stringProvider.getString(CommonStrings.message_reply_to_sender_created_poll)
            )
            // Encrypted target that isn't decrypted locally yet: leave the preview empty so it
            // fills in once decryption lands, rather than showing a notice/placeholder. We can't
            // tell whether it's a message or a reaction/etc until it's decrypted.
            getClearType() == EventType.ENCRYPTED -> null
            // Any non-message clear type (membership change, reaction, redaction, state, …):
            // render the notice text the timeline shows, greyed to look muted.
            getClearType() != EventType.MESSAGE ->
                "<font color=\"${noticeColorHex()}\">" + escapeHtml(noticePreview(timelineEvent, roomId)) + "</font>"
            else -> {
                // Real message (plaintext or decrypted). Prefer the latest edited content so the
                // preview reflects the current state rather than the original form.
                val content = latestContent ?: getClearContent().toModel<MessageContent>() ?: return null
                val formatted = (content as? MessageContentWithFormattedBody)?.matrixFormattedBody
                val preview = if (!formatted.isNullOrBlank()) {
                    // Strip any embedded mx-reply (parent is itself a reply) so the preview
                    // doesn't include the grandparent's quoted header.
                    truncateHtml(stripExistingMxReply(formatted))
                } else {
                    val rawBody = content.body
                    // Strip legacy "> <@user:server> previewline\n\n" reply prefix so the
                    // inline preview of a replied-to text reply doesn't include the quoted
                    // ancestor body.
                    val body = if (isReply()) ContentUtils.extractUsefulTextFromReply(rawBody) else rawBody
                    val clipped = if (body.length > MAX_PREVIEW_LENGTH) body.substring(0, MAX_PREVIEW_LENGTH) + "…" else body
                    escapeHtml(clipped)
                }
                // m.notice messages are shown muted/grey in the timeline; mirror that in the
                // reply preview so a replied-to notice looks the same.
                if (content.msgType == MessageType.MSGTYPE_NOTICE) {
                    "<font color=\"${noticeColorHex()}\">$preview</font>"
                } else {
                    preview
                }
            }
        }
    }

    private fun watchForDecryption(roomId: String, eventId: String) {
        if (!decryptionWatches.add(eventId)) return
        fetchScope.launch {
            try {
                repeat(DECRYPTION_WATCH_ATTEMPTS) {
                    delay(DECRYPTION_WATCH_INTERVAL_MS)
                    val event = getTimelineEvent(eventId, roomId)?.root
                    if (event != null && event.getClearType() != EventType.ENCRYPTED) {
                        _resolvedReplyTargets.tryEmit(eventId)
                        return@launch
                    }
                }
            } finally {
                decryptionWatches.remove(eventId)
            }
        }
    }

    private fun Event.noticePreview(timelineEvent: TimelineEvent?, roomId: String): String {
        val isDm = activeSessionHolder.getSafeActiveSession()
                ?.getRoom(roomId)
                ?.roomSummary()
                ?.isDirect
                .orFalse()
        return (timelineEvent?.let { noticeEventFormatter.format(it, isDm) }
                ?: noticeEventFormatter.format(this, senderId, isDm))
                ?.toString()
                ?: noticeEventFormatter.formatDebugOrUnhandled(this).toString()
    }

    // Truncate by *visible* character count while keeping the markup well-formed: never cut in
    // the middle of a tag or entity (which would leak literal "<spa" / "&am" text into the
    // rendered preview), and close any tags left open by the cut so styling doesn't bleed into
    // the rest of the reply block.
    private fun truncateHtml(html: String): String {
        val out = StringBuilder(html.length)
        val openTags = ArrayDeque<String>()
        var visible = 0
        var i = 0
        while (i < html.length) {
            if (visible >= MAX_PREVIEW_LENGTH) break
            when (val c = html[i]) {
                '<' -> {
                    val close = html.indexOf('>', i)
                    if (close == -1) break // dangling '<' — drop the rest rather than emit a partial tag
                    out.append(html, i, close + 1)
                    val inner = html.substring(i + 1, close).trim()
                    when {
                        inner.startsWith("!") || inner.startsWith("?") -> Unit // comment / declaration
                        inner.startsWith("/") -> {
                            val name = inner.drop(1).trim().lowercase()
                            val idx = openTags.indexOfLast { it == name }
                            if (idx != -1) openTags.removeAt(idx)
                        }
                        inner.endsWith("/") -> Unit // self-closing
                        else -> {
                            val name = inner.substringBefore(' ').substringBefore('\t').lowercase()
                            if (name !in VOID_HTML_TAGS) openTags.addLast(name)
                        }
                    }
                    i = close + 1
                }
                '&' -> {
                    val semi = html.indexOf(';', i)
                    if (semi != -1 && semi - i <= MAX_ENTITY_LENGTH) {
                        out.append(html, i, semi + 1)
                        i = semi + 1
                    } else {
                        out.append("&amp;")
                        i++
                    }
                    visible++
                }
                else -> {
                    out.append(c)
                    i++
                    visible++
                }
            }
        }
        if (i < html.length) out.append("…")
        while (openTags.isNotEmpty()) {
            out.append("</").append(openTags.removeLast()).append(">")
        }
        return out.toString()
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
