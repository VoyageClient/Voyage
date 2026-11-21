/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.api.debug.DebugLog
import org.matrix.android.sdk.api.debug.OperationDiagnostics
import org.matrix.android.sdk.internal.database.model.DraftEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.Draft as DraftRow

/** SQL access for `draft` (the UserDraftsEntity container is collapsed into room_id + draft_order). */
internal class DraftSqlStore(private val database: SessionSqlDatabase) {

    private val queries get() = database.draftQueries
    private val lastDiagnosticReads = object : LinkedHashMap<String, Long?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long?>?): Boolean = size > 256
    }

    fun getDrafts(roomId: String): List<DraftEntity> {
        val rows = queries.selectByRoom(roomId).executeAsList()
        if (DebugLog.enabled) {
            val revision = rows.lastOrNull()?.id
            val changed = synchronized(lastDiagnosticReads) {
                (!lastDiagnosticReads.containsKey(roomId) || lastDiagnosticReads[roomId] != revision).also { lastDiagnosticReads[roomId] = revision }
            }
            if (changed) DebugLog.i { "DRAFTDBG restored room=${OperationDiagnostics.id(roomId)} count=${rows.size} revision=$revision modes=${rows.map { it.draft_mode.takeIf { mode -> mode in DRAFT_MODES } ?: "unknown" }}" }
        } else synchronized(lastDiagnosticReads) { lastDiagnosticReads.clear() }
        return rows.map { it.toEntity() }
    }

    fun replaceDrafts(roomId: String, drafts: List<DraftEntity>) {
        DebugLog.i { "DRAFTDBG replace-request room=${OperationDiagnostics.id(roomId)} count=${drafts.size}" }
        queries.deleteByRoom(roomId)
        drafts.forEachIndexed { index, draft ->
            queries.insert(roomId, index.toLong(), draft.content, draft.draftMode, draft.linkedEventId)
        }
        DebugLog.i { "DRAFTDBG written-uncommitted room=${OperationDiagnostics.id(roomId)} count=${drafts.size}" }
    }

    fun deleteDrafts(roomId: String) = queries.deleteByRoom(roomId)

    private companion object {
        val DRAFT_MODES = setOf("REGULAR", "EDIT", "REPLY", "VOICE")
    }

    private fun DraftRow.toEntity(): DraftEntity = DraftEntity(
            content = content,
            draftMode = draft_mode,
            linkedEventId = linked_event_id,
    )
}
