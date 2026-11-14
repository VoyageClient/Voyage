/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.room.membership

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.CancellationException
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.events.getFixedRoomMemberContent
import org.matrix.android.sdk.internal.session.room.RoomAPI
import javax.inject.Inject

internal sealed interface OwnMembership {
    object Member : OwnMembership
    object Unknown : OwnMembership

    /** [membership] is null when the server only said we are not joined, so neither a kick nor a ban is known. */
    data class Departed(val memberEvent: Event?, val membership: Membership?) : OwnMembership
}

/** Asks the server for our own membership in a room, for when it refuses us something a member could do. */
internal class OwnMembershipProbe @Inject constructor(
        private val roomAPI: RoomAPI,
        @UserId private val userId: String,
) {

    suspend fun probe(roomId: String): OwnMembership {
        val result = probeMemberEvent(roomId)
        if (result !is OwnMembership.Departed || result.membership != null) return result
        return if (isBanConfirmedBySummary(roomId)) result.copy(membership = Membership.BAN) else result
    }

    private suspend fun probeMemberEvent(roomId: String): OwnMembership {
        return try {
            val event = executeRequest(null) { roomAPI.getRoomMemberEvent(roomId, userId) }
            val isEvent = event.type == EventType.STATE_ROOM_MEMBER && event.eventId != null
            // A server that ignores `format=event` answers with the bare content, which has no sender.
            val membership = if (isEvent) {
                event.getFixedRoomMemberContent()?.membership
            } else {
                executeRequest(null) { roomAPI.getRoomMemberContent(roomId, userId) }.membership
            }
            when {
                membership == null -> OwnMembership.Unknown
                membership.isActive() -> OwnMembership.Member
                else -> OwnMembership.Departed(event.takeIf { isEvent }, membership)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (failure: Throwable) {
            // Synapse lets a joined user, and a departed one who has not forgotten the room, read their own member
            // event. Everyone else gets a 403: banned, kicked or left and then forgotten, invited or knocking. A
            // world-readable room answers them as a guest: 404 when its state has no member event, as after a state reset.
            if (failure.isForbidden() || failure.isNotFound()) OwnMembership.Departed(null, null) else OwnMembership.Unknown
        }
    }

    /**
     * MSC3266 reports our current membership without regard to forgetting, but only for rooms that are public,
     * knockable or world-readable. It reports "never a member" as leave, so only a ban is conclusive.
     */
    private suspend fun isBanConfirmedBySummary(roomId: String): Boolean {
        val summary = try {
            executeRequest(null) { roomAPI.getRoomSummary(roomId) }
        } catch (e: CancellationException) {
            throw e
        } catch (failure: Throwable) {
            if (!failure.isUnrecognized()) return false
            try {
                executeRequest(null) { roomAPI.getRoomSummaryUnstable(roomId) }
            } catch (e: CancellationException) {
                throw e
            } catch (failure: Throwable) {
                return false
            }
        }
        return summary.membership == "ban"
    }

    private fun Throwable.isUnrecognized() = this is Failure.ServerError && (httpCode == 404 || httpCode == 405) &&
            error.code == MatrixError.M_UNRECOGNIZED

    private fun Throwable.isNotFound() = this is Failure.ServerError && httpCode == 404 && error.code == MatrixError.M_NOT_FOUND
}

internal fun Throwable.isForbidden() = this is Failure.ServerError && httpCode == 403 && error.code == MatrixError.M_FORBIDDEN

@JsonClass(generateAdapter = true)
internal data class RoomSummaryMembership(
        @Json(name = "membership")
        val membership: String?
)
