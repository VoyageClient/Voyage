/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.peeking

import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.session.room.Room
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.SessionScope
import javax.inject.Inject

/**
 * Registry of the rooms currently being previewed (peeked) without joining. Each roomId gets
 * one shared [PeekedRoom] so every consumer (RoomGetter fallback, timeline, member list…) sees
 * the same in-memory data. Session-scoped: unscoped would hand each injection site its own map.
 */
@SessionScope
internal class PeekedRoomManager @Inject constructor(
        @UserId private val myUserId: String,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val peekRoomInitialSyncTask: PeekRoomInitialSyncTask,
        private val peekLiveEventsTask: PeekLiveEventsTask,
        private val peekRoomMessagesTask: PeekRoomMessagesTask,
        private val peekRoomMembersTask: PeekRoomMembersTask,
        private val peekRoomUploadsTask: PeekRoomUploadsTask,
) {

    private val peekedRooms = LinkedHashMap<String, PeekedRoom>()

    fun getOrCreate(
            roomId: String,
            viaServers: List<String>,
            roomName: String?,
            roomAvatarUrl: String?,
            roomTopic: String?,
            roomAlias: String?,
    ): Room {
        synchronized(peekedRooms) {
            return peekedRooms.getOrPut(roomId) { createPeekedRoom(roomId, viaServers) }.also {
                it.dataSource.seed(roomName, roomAvatarUrl, roomTopic, roomAlias)
            }
        }
    }

    fun get(roomId: String): Room? {
        synchronized(peekedRooms) {
            return peekedRooms[roomId]
        }
    }

    /**
     * Stop the live peek (long-poll) but keep the room registered: other screens of the same
     * preview (profile, members, uploads, media viewer) may still be alive and resolve the room
     * through the getter; a later timeline re-entry recreates a fresh PeekTimeline.
     */
    fun release(roomId: String) {
        synchronized(peekedRooms) {
            peekedRooms[roomId]?.dispose()
            // Bound retention: browsing many previews must not accumulate data sources forever.
            // Only inactive (released) rooms are evicted, oldest registration first.
            val excess = peekedRooms.size - MAX_RETAINED_PEEKS
            if (excess > 0) {
                peekedRooms.entries
                        .filter { it.value.released }
                        .take(excess)
                        .forEach { peekedRooms.remove(it.key) }
            }
        }
    }

    /** Forget the room entirely (joined, or the peek proved unusable). */
    fun remove(roomId: String) {
        val room = synchronized(peekedRooms) { peekedRooms.remove(roomId) }
        room?.dispose()
    }

    fun viaServers(roomId: String): List<String> {
        return synchronized(peekedRooms) { peekedRooms[roomId]?.viaServers }.orEmpty()
    }

    private companion object {
        private const val MAX_RETAINED_PEEKS = 4
    }

    private fun createPeekedRoom(roomId: String, viaServers: List<String>): PeekedRoom {
        val dataSource = PeekedRoomDataSource(roomId, myUserId)
        return PeekedRoom(
                roomId = roomId,
                viaServers = viaServers,
                myUserId = myUserId,
                dataSource = dataSource,
                coroutineDispatchers = coroutineDispatchers,
                timelineFactory = {
                    PeekTimeline(
                            dataSource = dataSource,
                            coroutineDispatchers = coroutineDispatchers,
                            peekRoomInitialSyncTask = peekRoomInitialSyncTask,
                            peekLiveEventsTask = peekLiveEventsTask,
                            peekRoomMessagesTask = peekRoomMessagesTask,
                    )
                },
                peekRoomMembersTask = peekRoomMembersTask,
                peekRoomUploadsTask = peekRoomUploadsTask,
        )
    }
}
