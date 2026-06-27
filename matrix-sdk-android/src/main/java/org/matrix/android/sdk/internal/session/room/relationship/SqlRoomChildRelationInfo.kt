/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.relationship

import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.space.model.SpaceChildContent
import org.matrix.android.sdk.api.session.space.model.SpaceParentContent
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.sql.store.SessionStores

/** SQLDelight counterpart of [RoomChildRelationInfo]. */
internal class SqlRoomChildRelationInfo(
        private val stores: SessionStores,
        private val roomId: String,
) {

    fun getDirectChildrenDescriptions(): List<RoomChildRelationInfo.SpaceChildInfo> {
        return stores.currentStateEvent.getByRoomAndType(roomId, EventType.STATE_SPACE_CHILD)
                .mapNotNull { entity ->
                    ContentMapper.map(entity.root?.content).toModel<SpaceChildContent>()?.let { scc ->
                        scc.via?.let { via ->
                            RoomChildRelationInfo.SpaceChildInfo(
                                    roomId = entity.stateKey,
                                    order = scc.validOrder(),
                                    viaServers = via,
                            )
                        }
                    }
                }
                .sortedBy { it.order }
    }

    fun getParentDescriptions(): List<RoomChildRelationInfo.SpaceParentInfo> {
        return stores.currentStateEvent.getByRoomAndType(roomId, EventType.STATE_SPACE_PARENT)
                .mapNotNull { entity ->
                    ContentMapper.map(entity.root?.content).toModel<SpaceParentContent>()?.let { spc ->
                        spc.via?.let { via ->
                            RoomChildRelationInfo.SpaceParentInfo(
                                    roomId = entity.stateKey,
                                    canonical = spc.canonical ?: false,
                                    viaServers = via,
                                    stateEventSender = entity.root?.sender ?: "",
                            )
                        }
                    }
                }
    }
}
