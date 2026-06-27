/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.internal.database.model.DraftEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.Draft as DraftRow

/** SQL access for `draft` (the UserDraftsEntity container is collapsed into room_id + draft_order). */
internal class DraftSqlStore(private val database: SessionSqlDatabase) {

    private val queries get() = database.draftQueries

    fun getDrafts(roomId: String): List<DraftEntity> = queries.selectByRoom(roomId).executeAsList().map { it.toEntity() }

    fun replaceDrafts(roomId: String, drafts: List<DraftEntity>) {
        queries.deleteByRoom(roomId)
        drafts.forEachIndexed { index, draft ->
            queries.insert(roomId, index.toLong(), draft.content, draft.draftMode, draft.linkedEventId)
        }
    }

    fun deleteDrafts(roomId: String) = queries.deleteByRoom(roomId)

    private fun DraftRow.toEntity(): DraftEntity = DraftEntity(
            content = content,
            draftMode = draft_mode,
            linkedEventId = linked_event_id,
    )
}
