/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.typing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.sender.SenderInfo

/** Fake typing users set over adb by debug builds; nothing sets them in release. */
object DebugTypingUsers {

    private const val ALL_ROOMS = "*"
    private val fakesByRoom = MutableStateFlow<Map<String, List<SenderInfo>>>(emptyMap())

    /** A null [roomId] applies to every room; an empty [users] list removes the fake. */
    fun set(roomId: String?, users: List<SenderInfo>) {
        val key = roomId ?: ALL_ROOMS
        fakesByRoom.update { if (users.isEmpty()) it - key else it + (key to users) }
    }

    fun applyTo(summaries: Flow<RoomSummary>): Flow<RoomSummary> = combine(summaries, fakesByRoom) { summary, fakes ->
        val fake = fakes[summary.roomId] ?: fakes[ALL_ROOMS]
        if (fake == null) summary else summary.copy(typingUsers = fake)
    }
}
