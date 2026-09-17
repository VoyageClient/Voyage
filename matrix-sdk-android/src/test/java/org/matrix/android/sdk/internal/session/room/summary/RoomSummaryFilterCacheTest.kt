/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.summary

import io.mockk.mockk
import org.amshove.kluent.shouldBeEqualTo
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.android.sdk.api.session.room.RoomSortOrder
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.internal.database.model.RoomSummaryEntity
import org.matrix.android.sdk.internal.database.model.RoomTagEntity
import org.matrix.android.sdk.internal.session.sync.SyncImportState
import org.matrix.android.sdk.test.fakes.FakeSessionDatabase
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class RoomSummaryFilterCacheTest {

    private val db = FakeSessionDatabase()
    private val source = RoomSummaryDataSource(
            database = db.database,
            dispatcher = db.dispatcher,
            roomSummaryMapper = mockk(),
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
}
