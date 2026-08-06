/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.redaction

import org.matrix.android.sdk.api.session.redaction.PreservationOrigin
import org.matrix.android.sdk.api.session.redaction.PreservedEventContent
import org.matrix.android.sdk.api.session.redaction.RedactedContentService
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.session.search.index.EventIndexer
import javax.inject.Inject

internal class DefaultRedactedContentService @Inject constructor(
        private val store: RedactedContentStore,
        private val eventIndexer: dagger.Lazy<EventIndexer>,
) : RedactedContentService {

    override suspend fun getPreservedContent(eventId: String): PreservedEventContent? {
        return store.get(eventId)?.let { it.toApi() }
    }

    override suspend fun getPreservedContentInRoom(roomId: String): List<PreservedEventContent> {
        return store.getForRoom(roomId).map { it.toApi() }
    }

    override suspend fun preserve(content: PreservedEventContent) {
        val preserved = PreservedContent(
                eventId = content.eventId,
                roomId = content.roomId,
                content = ContentMapper.map(content.content).orEmpty(),
                clearType = content.clearType,
                sender = content.senderId,
                originServerTs = content.originServerTs,
                source = when (content.origin) {
                    PreservationOrigin.FETCHED -> PreservationSource.MSC2815
                    PreservationOrigin.CAPTURED -> PreservationSource.CAPTURED
                },
                fetchedAt = content.preservedAt,
        )
        store.put(preserved)
        // Unconditional, not only for MSC2815: a capture can lose the race with the redaction that
        // dropped the row. indexPreservedContent is idempotent and skips events still live.
        eventIndexer.get().indexPreservedContent(preserved)
    }

    override suspend fun roomsWithPreservedContent() = store.roomsWithContent()

    override suspend fun clearExcept(roomIds: Collection<String>) {
        val dropped = store.eventIdsOutsideRooms(roomIds)
        store.clearExcept(roomIds)
        eventIndexer.get().dropIndexedRedactions(dropped)
    }

    private fun PreservedContent.toApi() = PreservedEventContent(
            eventId = eventId,
            roomId = roomId,
            content = ContentMapper.map(content).orEmpty(),
            clearType = clearType,
            senderId = sender,
            originServerTs = originServerTs,
            origin = when (source) {
                PreservationSource.MSC2815 -> PreservationOrigin.FETCHED
                PreservationSource.CAPTURED -> PreservationOrigin.CAPTURED
            },
            preservedAt = fetchedAt,
    )
}
