/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.Space_child_summary as SpaceChildRow
import org.matrix.android.sdk.internal.database.sql.Space_parent_summary as SpaceParentRow

/** Raw row access for `space_child_summary` / `space_parent_summary`; assembly into entities (with the
 * recursive room_summary refs) is done by [RoomSummarySqlStore]. */
internal class SpaceSqlStore(private val database: SessionSqlDatabase) {

    private val queries get() = database.spaceQueries

    fun childRows(spaceRoomId: String): List<SpaceChildRow> = queries.selectChildren(spaceRoomId).executeAsList()

    fun parentRows(roomId: String): List<SpaceParentRow> = queries.selectParents(roomId).executeAsList()

    fun replaceChildren(spaceRoomId: String, children: List<SpaceChildInsert>) {
        queries.deleteChildren(spaceRoomId)
        children.forEach {
            queries.insertChild(spaceRoomId, it.order, it.autoJoin, it.suggested, it.childRoomId, it.childSummaryRoomId, it.viaServers)
        }
    }

    fun replaceParents(roomId: String, parents: List<SpaceParentInsert>) {
        queries.deleteParents(roomId)
        parents.forEach {
            queries.insertParent(roomId, it.canonical, it.parentRoomId, it.parentSummaryRoomId, it.viaServers)
        }
    }

    fun deleteChildren(spaceRoomId: String) = queries.deleteChildren(spaceRoomId)

    fun deleteParents(roomId: String) = queries.deleteParents(roomId)

    data class SpaceChildInsert(
            val order: String?,
            val autoJoin: Long?,
            val suggested: Long?,
            val childRoomId: String?,
            val childSummaryRoomId: String?,
            val viaServers: String,
    )

    data class SpaceParentInsert(
            val canonical: Long?,
            val parentRoomId: String?,
            val parentSummaryRoomId: String?,
            val viaServers: String,
    )
}
