/*
 * Copyright 2021 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.session.permalinks

import android.os.SystemClock
import android.util.LruCache
import org.matrix.android.sdk.api.MatrixPatterns.getServerName
import org.matrix.android.sdk.api.session.room.members.roomMemberQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.room.RoomGetter
import org.matrix.android.sdk.internal.session.room.powerlevels.getRoomPowerLevels
import org.matrix.android.sdk.internal.session.room.state.StateEventDataSource
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Provider

// Session-scoped so the per-room server counts are shared by every caller.
@SessionScope
internal class ViaParameterFinder @Inject constructor(
        @UserId private val userId: String,
        private val roomGetterProvider: Provider<RoomGetter>,
        private val stateEventDataSource: StateEventDataSource,
        private val stores: SessionStores,
) {

    private class ServerCounts(val byServer: Map<String, Int>, val computedAt: Long)

    // A timeline of forwards asks once per message, and a large room has tens of thousands of members to
    // count. Via servers are only join hints, so a few minutes' staleness costs nothing.
    private val serverCounts = LruCache<String, ServerCounts>(SERVER_COUNTS_CACHE_SIZE)

    fun computeViaParams(roomId: String, max: Int): List<String> {
        return computeViaParams(userId, roomId, max)
    }

    /**
     * Compute the via parameters.
     * Take up to 3 homeserver domains, taking the most representative one regarding room members and including the
     * current user one.
     */
    fun computeViaParams(userId: String, roomId: String): String {
        return asUrlViaParameters(computeViaParams(userId, roomId, 3))
    }

    fun asUrlViaParameters(viaList: List<String>): String {
        return viaList.joinToString(prefix = "?via=", separator = "&via=") { URLEncoder.encode(it, "utf-8") }
    }

    fun computeViaParams(userId: String, roomId: String, max: Int): List<String> {
        val userHomeserver = userId.getServerName()
        return joinedServerCounts(roomId)
                .toMutableMap()
                // Ensure the user homeserver will be included
                .apply { this[userHomeserver] = Int.MAX_VALUE }
                .let { map -> map.keys.sortedByDescending { map[it] } }
                .take(max)
    }

    private fun joinedServerCounts(roomId: String): Map<String, Int> {
        val now = SystemClock.elapsedRealtime()
        serverCounts.get(roomId)?.takeIf { now - it.computedAt < SERVER_COUNTS_TTL_MS }?.let { return it.byServer }
        // Split directly rather than through getServerName(), which validates every id against a regex in debug builds.
        val counts = stores.roomMember.getUserIdsByRoomAndMembership(roomId, Membership.JOIN)
                .groupingBy { it.substringAfter(':') }
                .eachCount()
        serverCounts.put(roomId, ServerCounts(counts, now))
        return counts
    }

    // not used much for now but as per MSC1772
    // the via parameter of m.space.child must contain a via key which gives a list of candidate servers that can be used to join the room.
    // It is possible for the list of candidate servers and the list of authorised servers to diverge.
    // It may not be possible for a user to join a room if there's no overlap between these
    fun computeViaParamsForRestricted(roomId: String, max: Int): List<String> {
        val userThatCanInvite = roomGetterProvider.get().getRoom(roomId)
                ?.membershipService()
                ?.getRoomMembers(roomMemberQueryParams { memberships = listOf(Membership.JOIN) })
                ?.map { it.userId }
                ?.filter { userCanInvite(userId, roomId) }
                .orEmpty()
                .toSet()

        return userThatCanInvite.map { it.getServerName() }
                .groupBy { it }
                .mapValues { it.value.size }
                .toMutableMap()
                .let { map -> map.keys.sortedByDescending { map[it] } }
                .take(max)
    }

    fun userCanInvite(userId: String, roomId: String): Boolean {
        val roomPowerLevels = stateEventDataSource.getRoomPowerLevels(roomId)
        return roomPowerLevels.isUserAbleToInvite(userId)
    }

    private companion object {
        const val SERVER_COUNTS_CACHE_SIZE = 64
        const val SERVER_COUNTS_TTL_MS = 5 * 60_000L
    }
}
