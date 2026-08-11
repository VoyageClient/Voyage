/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.list.watched

import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.room.model.WatchedRoomInfo

/**
 * The /watch registry: previewable rooms the user follows without joining, kept in synced account
 * data so it roams across devices.
 */
object WatchedRooms {

    const val ACCOUNT_DATA_TYPE = "im.voyage.watched_rooms"

    /** A peek reports every participating server; a handful of routes is all a join/peek needs. */
    const val MAX_VIA_SERVERS = 10

    fun parse(content: Content?): List<WatchedRoomInfo> {
        return (content?.get("rooms") as? List<*>).orEmpty().mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            val roomId = map["room_id"] as? String ?: return@mapNotNull null
            WatchedRoomInfo(
                    roomId = roomId,
                    viaServers = (map["via"] as? List<*>)?.filterIsInstance<String>().orEmpty().take(MAX_VIA_SERVERS),
                    name = map["name"] as? String,
                    avatarUrl = map["avatar_url"] as? String,
                    topic = map["topic"] as? String,
                    alias = map["alias"] as? String,
            )
        }
    }

    private fun toContent(rooms: List<WatchedRoomInfo>): Content = mapOf(
            "rooms" to rooms.map { info ->
                buildMap {
                    put("room_id", info.roomId)
                    if (info.viaServers.isNotEmpty()) put("via", info.viaServers.take(MAX_VIA_SERVERS))
                    info.name?.let { put("name", it) }
                    info.avatarUrl?.let { put("avatar_url", it) }
                    info.topic?.let { put("topic", it) }
                    info.alias?.let { put("alias", it) }
                }
            }
    )

    fun get(session: Session): List<WatchedRoomInfo> =
            parse(session.accountDataService().getUserAccountDataEvent(ACCOUNT_DATA_TYPE)?.content)

    suspend fun add(session: Session, info: WatchedRoomInfo) {
        val updated = get(session).filter { it.roomId != info.roomId } + info
        session.accountDataService().updateUserAccountData(ACCOUNT_DATA_TYPE, toContent(updated))
    }

    suspend fun remove(session: Session, roomIdOrAlias: String): Boolean {
        val current = get(session)
        val updated = current.filter { it.roomId != roomIdOrAlias && it.alias != roomIdOrAlias }
        if (updated.size == current.size) return false
        session.accountDataService().updateUserAccountData(ACCOUNT_DATA_TYPE, toContent(updated))
        return true
    }
}
