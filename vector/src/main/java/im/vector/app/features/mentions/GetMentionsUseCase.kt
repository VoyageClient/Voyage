/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.mentions

import im.vector.app.core.date.DateFormatKind
import im.vector.app.core.date.VectorDateFormatter
import im.vector.app.features.home.room.detail.timeline.format.DisplayableEventFormatter
import im.vector.app.features.home.room.detail.timeline.helper.SenderProfileResolver
import im.vector.app.features.home.room.detail.timeline.tools.prepareForDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.api.session.room.getTimelineEvent
import org.matrix.android.sdk.api.session.room.mentions.MentionKind
import org.matrix.android.sdk.api.session.room.mentions.MentionsQueryParams
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import javax.inject.Inject

/** Lookups in flight at once, and how long one may take before the rest stop waiting on it. */
private const val PROFILE_BATCH = 8
private const val PROFILE_TIMEOUT_MS = 10_000L

data class MentionListItem(
        val event: TimelineEvent,
        val roomSummary: RoomSummary,
        val kind: MentionKind,
        /** Rendered here rather than at model-build time: formatting a few hundred bodies blocks the list. */
        val body: CharSequence,
        val formattedDate: String,
        val senderName: CharSequence,
        val roomName: CharSequence,
)

class GetMentionsUseCase @Inject constructor(
        private val session: Session,
        private val senderProfileResolver: SenderProfileResolver,
        private val displayableEventFormatter: DisplayableEventFormatter,
        private val dateFormatter: VectorDateFormatter,
) {

    data class Page(val items: List<MentionListItem>, val nextToken: String?)

    suspend fun execute(filter: MentionsFilter, from: String? = null): Page = withContext(Dispatchers.IO) {
        val result = session.roomService()
                .getMentions(MentionsQueryParams(
                        includeRoomMentions = filter.includeRoomMentions,
                        includeKeywords = filter.includeKeywords,
                        from = from,
                ))
        // Several mentions usually share a room; each lookup is a DB read, so resolve each room once.
        val profiles = senderProfileResolver.newSession()
        val summaries = HashMap<String, RoomSummary?>()
        val rooms = HashMap<String, Room?>()
        result.events
                .mapNotNull { mention ->
                    val summary = summaries.getOrPut(mention.roomId) { session.roomService().getRoomSummary(mention.roomId) }
                            ?: return@mapNotNull null
                    if (filter.excludeDms && summary.isDirect) return@mapNotNull null
                    val room = rooms.getOrPut(mention.roomId) { session.getRoom(mention.roomId) } ?: return@mapNotNull null
                    val timelineEvent = room.getTimelineEvent(mention.eventId)
                            ?.also { event ->
                                if (event.root.getClearType() == EventType.ENCRYPTED) {
                                    session.eventService().requestDecryption(event.root)
                                }
                            }
                    // A hit the search index reached but sync never stored has no timeline row to read.
                            ?: mention.event.toTimelineEvent()
                    timelineEvent.takeUnless { it.root.isRedacted() }
                            ?.let { it.copy(senderInfo = profiles.resolve(mention.roomId, it.senderInfo)) }
                            ?.let { event ->
                                MentionListItem(
                                        event = event,
                                        roomSummary = summary,
                                        kind = mention.kind,
                                        body = displayableEventFormatter.format(event, isDm = summary.isDirect, appendAuthor = false),
                                        formattedDate = dateFormatter.format(event.root.originServerTs, DateFormatKind.DEFAULT_DATE_AND_TIME),
                                        senderName = event.senderInfo.disambiguatedDisplayName.prepareForDisplay(),
                                        roomName = summary.displayName.prepareForDisplay(),
                                )
                            }
                }
                .also { prefetchSenderProfiles(it) }
                .let { Page(items = it, nextToken = result.nextToken) }
    }

    /**
     * A row sourced from the search index or `/notifications` has no stored sender profile, so the
     * resolver can name it no better than its own id. Fetch those senders' profiles, which fills the
     * user table for good, and report the list again after each batch so names appear as they arrive.
     *
     * Batched with a timeout rather than awaited as a whole: one request that never returns would
     * otherwise strand every row waiting on it.
     */
    suspend fun fillSenderProfiles(items: List<MentionListItem>, onProgress: suspend (List<MentionListItem>) -> Unit) {
        val unknown = items
                .filter { it.needsProfile() }
                .mapTo(LinkedHashSet()) { it.event.senderInfo.userId }
                .filter { session.userService().getUser(it) == null }
        if (unknown.isEmpty()) return
        var current = items
        unknown.chunked(PROFILE_BATCH).forEach { batch ->
            val resolved = coroutineScope {
                batch.map { userId ->
                    async { withTimeoutOrNull(PROFILE_TIMEOUT_MS) { tryOrNull { session.userService().resolveUser(userId) } } }
                }.awaitAll()
            }.filterNotNull()
            if (resolved.isEmpty()) return@forEach
            // The user table knows them now, so the resolver can name what it could not before.
            val profiles = senderProfileResolver.newSession()
            val named = resolved.mapTo(HashSet()) { it.userId }
            current = current.map { item ->
                if (item.event.senderInfo.userId !in named || !item.needsProfile()) item
                else item.withSenderProfile(profiles.resolve(item.roomSummary.roomId, item.event.senderInfo.blankProfile()))
            }
            onProgress(current)
        }
    }

    /** A row whose sender we could name no better than their own id still wants a real profile. */
    private fun MentionListItem.needsProfile(): Boolean =
            event.senderInfo.displayName.isNullOrBlank() ||
                    event.senderInfo.displayName == event.senderInfo.userId ||
                    event.senderInfo.avatarUrl == null

    private fun SenderInfo.blankProfile() = copy(displayName = null, avatarUrl = null)

    private fun MentionListItem.withSenderProfile(senderInfo: SenderInfo) = copy(
            event = event.copy(senderInfo = senderInfo),
            senderName = senderInfo.disambiguatedDisplayName.prepareForDisplay(),
    )

    /**
     * Ask for each sender's profile fields now rather than letting the first bind of their avatar do
     * it: this list spans every room, so that would be a few hundred requests fired while scrolling.
     * The service dedups and backs off on failure, so this is a hint, not a guarantee.
     */
    private fun prefetchSenderProfiles(items: List<MentionListItem>) {
        items.mapTo(LinkedHashSet()) { it.event.senderInfo.userId }
                .forEach { session.profileService().prefetchProfileFields(it) }
    }

    // Nothing stored the sender's profile as it was, so it is left unknown for the resolver to fill in.
    private fun Event.toTimelineEvent() = TimelineEvent(
            root = this,
            localId = 0L,
            eventId = eventId.orEmpty(),
            senderInfo = SenderInfo(
                    userId = senderId.orEmpty(),
                    displayName = null,
                    isUniqueDisplayName = true,
                    avatarUrl = null,
            ),
    )
}
