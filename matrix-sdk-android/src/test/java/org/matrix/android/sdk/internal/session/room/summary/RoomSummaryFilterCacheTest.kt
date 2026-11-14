/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.summary

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.room.RoomSortOrder
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.RoomType
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.api.session.room.spaceSummaryQueryParams
import org.matrix.android.sdk.internal.database.mapper.RoomSummaryMapper
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntity
import org.matrix.android.sdk.internal.database.model.RoomTagEntity
import org.matrix.android.sdk.internal.session.sync.SyncImportState
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class RoomSummaryFilterCacheTest {

    private val db = FakeSessionDatabase()
    private val mapper = mockk<RoomSummaryMapper>()
    private val source = RoomSummaryDataSource(
            database = db.database,
            dispatcher = db.dispatcher,
            roomSummaryMapper = mapper,
            localRoomSummaryMapper = mockk(),
            stores = db.stores,
            previewInvalidation = RoomSummaryPreviewInvalidation(),
            syncImportState = SyncImportState(),
    )

    @After
    fun tearDown() {
        source.sectionFetchExecutor.shutdownNow()
        db.close()
    }

    @Test
    fun `tag-only changes refresh cached room filters`() {
        val roomId = "!room:example.org"
        db.stores.roomSummary.upsert(RoomSummaryEntity(roomId = roomId).apply { membership = Membership.JOIN })
        val params = roomSummaryQueryParams { activeTagFilter = "u.work" }

        source.filteredSortedRows(params, RoomSortOrder.NONE).size shouldBeEqualTo 0

        db.stores.roomTag.replaceTags(roomId, listOf(RoomTagEntity(tagName = "u.work")))

        source.filteredSortedRows(params, RoomSortOrder.NONE).map { it.room_id } shouldBeEqualTo listOf(roomId)

        db.stores.roomTag.deleteTags(roomId)

        source.filteredSortedRows(params, RoomSortOrder.NONE).size shouldBeEqualTo 0
    }

    @Test
    fun `space observers ignore unrelated rooms but still receive changed space data`() = runBlocking<Unit> {
        val spaceId = "!space:example.org"
        val roomId = "!room:example.org"
        every { mapper.map(any(), any()) } answers {
            val entity = firstArg<RoomSummaryEntity>()
            RoomSummary(
                    roomId = entity.roomId, displayName = entity.name.orEmpty(),
                    isEncrypted = false, encryptionEventTs = null, typingUsers = emptyList(),
            )
        }
        val space = RoomSummaryEntity(roomId = spaceId, roomType = RoomType.SPACE).apply {
            membership = Membership.JOIN
            name = "Space"
        }
        withContext(db.dispatcher) {
            db.stores.roomSummary.upsert(space)
            db.stores.roomSummary.upsert(RoomSummaryEntity(roomId = roomId).apply { membership = Membership.JOIN })
        }
        val updates = Channel<List<RoomSummary>>(Channel.UNLIMITED)
        val job = launch { source.getSpaceSummariesFlow(spaceSummaryQueryParams {}).collect { updates.send(it) } }
        try {
            withTimeout(5000) { updates.receive() }.single().displayName shouldBeEqualTo "Space"
            withContext(db.dispatcher) {
                db.stores.roomSummary.upsert(RoomSummaryEntity(roomId = roomId).apply {
                    membership = Membership.JOIN
                    notificationCount = 3
                })
            }
            withTimeoutOrNull(500) { updates.receive() } shouldBeEqualTo null
            withContext(db.dispatcher) {
                space.name = "Renamed space"
                db.stores.roomSummary.upsert(space)
            }
            withTimeout(5000) { updates.receive() }.single().displayName shouldBeEqualTo "Renamed space"
        } finally {
            job.cancelAndJoin()
            updates.close()
        }
    }
}
