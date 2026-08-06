/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.summary

import io.mockk.every
import io.mockk.mockk
import org.amshove.kluent.shouldBe
import org.amshove.kluent.shouldBeNull
import org.junit.Test
import org.matrix.android.sdk.api.MatrixConfiguration
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.RelationType
import org.matrix.android.sdk.api.session.events.model.UnsignedData
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.MoshiProvider

private const val A_ROOM_ID = "!room:example.org"
private const val A_CHUNK_ID = 7L
private const val A_SENDER = "@bob:example.org"

class SqlRoomSummaryEventsHelperTest {

    private val stores = mockk<SessionStores>(relaxed = true)
    private val configuration = mockk<MatrixConfiguration> { every { customEventTypesProvider } returns null }

    private val helper = SqlRoomSummaryEventsHelper(configuration)

    private fun liveChunkHolds(vararg events: TimelineEventEntity) {
        every { stores.user.getIgnoredUserIds() } returns emptyList()
        every { stores.timelineEvent.getSendingByRoom(A_ROOM_ID) } returns emptyList()
        every { stores.chunk.lastForward(A_ROOM_ID) } returns mockk { every { id } returns A_CHUNK_ID }
        every { stores.timelineEvent.getByChunkNewest(A_CHUNK_ID, any()) } returns events.toList()
    }

    private fun event(
            eventId: String,
            type: String = EventType.MESSAGE,
            redacted: Boolean = false,
            isUseless: Boolean = false,
            relationType: String? = null,
    ): TimelineEventEntity {
        val content = if (relationType == null) {
            mapOf("body" to "hi")
        } else {
            mapOf("body" to "hi", "m.relates_to" to mapOf("rel_type" to relationType, "event_id" to "\$target"))
        }
        val unsigned = if (redacted) {
            MoshiProvider.providesMoshi().adapter(UnsignedData::class.java)
                    .toJson(UnsignedData(age = null, redactedEvent = null, redactedBy = "\$redaction"))
        } else {
            null
        }
        val root = EventEntity(
                eventId = eventId,
                roomId = A_ROOM_ID,
                type = type,
                content = MoshiProvider.providesMoshi().adapter(Map::class.java).toJson(content),
                isUseless = isUseless,
                sender = A_SENDER,
                originServerTs = 1_000L,
                unsignedData = unsigned,
        )
        return TimelineEventEntity(localId = 1L, eventId = eventId, roomId = A_ROOM_ID, displayIndex = 0, root = root)
    }

    /**
     * A deleted message keeps its place as the room's last activity, rendered as the placeholder. Skipping
     * it made the room list disagree with the timeline, and disagree with itself: the persisted pointer
     * showed the placeholder until something recomputed the preview and silently moved it back.
     */
    @Test
    fun `given the newest message was redacted, then it is still the preview`() {
        val redacted = event("\$redacted", redacted = true)
        liveChunkHolds(redacted, event("\$older"))

        helper.getLatestPreviewableEvent(stores, A_ROOM_ID)?.eventId shouldBe "\$redacted"
    }

    /** And the room-open pass has to agree with the sync pass, which is what "inconsistent" meant. */
    @Test
    fun `given a thorough scan, then a redacted newest message is still the preview`() {
        val redacted = event("\$redacted", redacted = true)
        liveChunkHolds(redacted, event("\$older"))

        helper.getLatestPreviewableEvent(stores, A_ROOM_ID, thorough = true)?.eventId shouldBe "\$redacted"
    }

    /** Removing a reaction is an undo, not a deletion: it must not caption the room. */
    @Test
    fun `given the newest event is an undone reaction, then the last message is the preview`() {
        liveChunkHolds(event("\$reaction", type = EventType.REACTION, redacted = true), event("\$message"))

        helper.getLatestPreviewableEvent(stores, A_ROOM_ID)?.eventId shouldBe "\$message"
    }

    /** A live reaction still previews, as before — only the redacted case is special. */
    @Test
    fun `given the newest event is a reaction, then it is the preview`() {
        liveChunkHolds(event("\$reaction", type = EventType.REACTION), event("\$message"))

        helper.getLatestPreviewableEvent(stores, A_ROOM_ID)?.eventId shouldBe "\$reaction"
    }

    /**
     * Redacting an edit reverts the original, which is what the room shows, so the edit must not preview.
     * The prune destroys `m.relates_to`, so [org.matrix.android.sdk.internal.session.room.prune
     * .RedactionEventProcessor] records the verdict as `is_useless` while the relation is still readable —
     * this asserts the flag is honoured, since by this point it is the only trace left.
     */
    @Test
    fun `given a redacted edit marked useless, then the last message is the preview`() {
        liveChunkHolds(event("\$edit", redacted = true, isUseless = true), event("\$message"))

        helper.getLatestPreviewableEvent(stores, A_ROOM_ID)?.eventId shouldBe "\$message"
    }

    /** An un-redacted edit is folded into its target, so it never captions the room either. */
    @Test
    fun `given the newest event is an edit, then the last message is the preview`() {
        liveChunkHolds(event("\$edit", relationType = RelationType.REPLACE), event("\$message"))

        helper.getLatestPreviewableEvent(stores, A_ROOM_ID)?.eventId shouldBe "\$message"
    }

    /** Nothing previewable in the newest slice: the caller keeps whatever is already persisted. */
    @Test
    fun `given only undone reactions, then no preview is found`() {
        liveChunkHolds(event("\$reaction", type = EventType.REACTION, redacted = true))
        every { stores.timelineEvent.getByRoomTypesNewest(A_ROOM_ID, any(), any()) } returns emptyList()

        helper.getLatestPreviewableEvent(stores, A_ROOM_ID).shouldBeNull()
    }
}
