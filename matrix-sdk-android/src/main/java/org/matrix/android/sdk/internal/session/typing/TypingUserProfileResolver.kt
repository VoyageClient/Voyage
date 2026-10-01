/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.typing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import org.matrix.android.sdk.internal.database.mapper.overriddenSenderInfo
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.network.GlobalErrorReceiver
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.SessionCoroutineScopeHolder
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.room.RoomAPI
import timber.log.Timber
import javax.inject.Inject

/**
 * With lazy-loaded members, someone who types without having posted in the synced window has no member
 * row, so their typing notice would show a bare user id. Fetches that one member event on demand.
 */
@SessionScope
internal class TypingUserProfileResolver @Inject constructor(
        private val roomAPI: RoomAPI,
        private val globalErrorReceiver: GlobalErrorReceiver,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val typingUsersTracker: DefaultTypingUsersTracker,
        private val scopeHolder: SessionCoroutineScopeHolder,
) {

    data class Profile(val displayName: String?, val avatarUrl: String?)

    // Failures are cached as an empty profile too: typing events repeat every few seconds.
    private val cache = object : LinkedHashMap<String, Profile>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Profile>?) = size > MAX_ENTRIES
    }
    private val inFlight = HashSet<String>()

    /** The fetched profile, or null after scheduling a fetch that re-publishes the room's typing users. */
    fun getOrFetch(roomId: String, userId: String): Profile? {
        val key = "$roomId|$userId"
        synchronized(cache) {
            cache[key]?.let { return it }
            if (!inFlight.add(key)) return null
        }
        scopeHolder.scope.launch {
            val profile = try {
                executeRequest(globalErrorReceiver) { roomAPI.getRoomMemberContent(roomId, userId) }
                        .let { Profile(it.displayName, it.avatarUrl) }
            } catch (failure: Throwable) {
                Timber.w(failure, "Failed to fetch member $userId of $roomId for typing notice")
                Profile(null, null)
            }
            synchronized(cache) {
                cache[key] = profile
                inFlight.remove(key)
            }
            if (profile.displayName == null && profile.avatarUrl == null) return@launch
            database.awaitDbTransaction(dispatcher) {
                val current = typingUsersTracker.getTypingUsers(roomId)
                if (current.none { it.userId == userId }) return@awaitDbTransaction
                val updated = current.map {
                    if (it.userId != userId) return@map it
                    overriddenSenderInfo(
                            userId = userId,
                            displayName = profile.displayName ?: it.displayName,
                            isUniqueDisplayName = isUniqueAmongMembers(roomId, profile.displayName),
                            avatarUrl = profile.avatarUrl ?: it.avatarUrl,
                    )
                }
                if (typingUsersTracker.setTypingUsersFromRoom(roomId, updated)) {
                    stores.roomSummary.touch(roomId)
                }
            }
        }
        return null
    }

    fun isUniqueAmongMembers(roomId: String, displayName: String?): Boolean {
        if (displayName.isNullOrEmpty()) return true
        return stores.roomMember.getByRoom(roomId).none { it.displayName == displayName }
    }

    private companion object {
        const val MAX_ENTRIES = 500
    }
}
