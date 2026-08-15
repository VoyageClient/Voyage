/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.sync.sliding

import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.sync.model.InvitedRoomSync
import org.matrix.android.sdk.api.session.sync.model.KnockedRoomSync
import org.matrix.android.sdk.api.session.sync.model.LazyRoomSyncEphemeral
import org.matrix.android.sdk.api.session.sync.model.RoomInviteState
import org.matrix.android.sdk.api.session.sync.model.RoomSync
import org.matrix.android.sdk.api.session.sync.model.RoomSyncAccountData
import org.matrix.android.sdk.api.session.sync.model.RoomSyncEphemeral
import org.matrix.android.sdk.api.session.sync.model.RoomSyncHeroProfile
import org.matrix.android.sdk.api.session.sync.model.RoomSyncState
import org.matrix.android.sdk.api.session.sync.model.RoomSyncSummary
import org.matrix.android.sdk.api.session.sync.model.RoomSyncTimeline
import org.matrix.android.sdk.api.session.sync.model.RoomsSyncResponse
import org.matrix.android.sdk.api.session.sync.model.SyncResponse
import org.matrix.android.sdk.api.session.sync.model.ToDeviceSyncResponse
import org.matrix.android.sdk.api.session.sync.model.UserAccountDataSync
import org.matrix.android.sdk.api.session.sync.model.UserProfileSyncUpdate
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.events.getFixedRoomMemberContent
import javax.inject.Inject

/**
 * Reshapes a sliding-sync response into the sync v2 shape, so the whole existing handler stack
 * (SyncResponseHandler, SqlRoomSyncHandler, aggregators, push rules) drives both transports.
 */
internal class SlidingSyncTranslator @Inject constructor(
        @UserId private val userId: String,
) {

    fun toSyncResponse(response: SlidingSyncResponse): SyncResponse {
        val extensions = response.extensions
        val roomAccountData = extensions?.accountData?.rooms.orEmpty()
        val receipts = extensions?.receipts?.rooms.orEmpty()
        val typing = extensions?.typing?.rooms.orEmpty()

        val join = LinkedHashMap<String, RoomSync>()
        val invite = LinkedHashMap<String, InvitedRoomSync>()
        val leave = LinkedHashMap<String, RoomSync>()
        val knock = LinkedHashMap<String, KnockedRoomSync>()

        response.rooms.orEmpty().forEach { (roomId, room) ->
            val ephemeral = listOfNotNull(receipts[roomId], typing[roomId])
            when (room.membership()) {
                Membership.INVITE -> invite[roomId] = InvitedRoomSync(
                        inviteState = RoomInviteState(room.strippedStateEvents.orEmpty())
                )
                Membership.KNOCK -> knock[roomId] = KnockedRoomSync(
                        knockState = RoomInviteState(room.strippedStateEvents.orEmpty())
                )
                Membership.LEAVE,
                Membership.BAN -> leave[roomId] = room.toRoomSync(roomAccountData[roomId], emptyList())
                else -> join[roomId] = room.toRoomSync(roomAccountData[roomId], ephemeral)
            }
        }

        // Tags, receipts and typing arrive only in the extensions block; a room with no timeline/state
        // update is absent from `rooms`, so without a synthetic entry its echo would be dropped.
        (roomAccountData.keys + receipts.keys + typing.keys)
                .filterNot { it in join || it in invite || it in leave || it in knock }
                .forEach { roomId ->
                    val ephemeral = listOfNotNull(receipts[roomId], typing[roomId])
                    join[roomId] = RoomSync(
                            accountData = roomAccountData[roomId]?.let { RoomSyncAccountData(events = it) },
                            ephemeral = ephemeral.takeIf { it.isNotEmpty() }
                                    ?.let { LazyRoomSyncEphemeral.Parsed(RoomSyncEphemeral(events = it)) },
                    )
                }

        val e2ee = extensions?.e2ee
        return SyncResponse(
                // Informational only: the pos is persisted separately, since it is not a v2 since-token.
                nextBatch = response.pos,
                accountData = extensions?.accountData?.global?.let { UserAccountDataSync(it) },
                toDevice = extensions?.toDevice?.events?.let { ToDeviceSyncResponse(it) },
                deviceLists = e2ee?.deviceLists,
                deviceOneTimeKeysCount = e2ee?.deviceOneTimeKeysCount,
                stableDeviceUnusedFallbackKeyTypes = e2ee?.deviceUnusedFallbackKeyTypes,
                devDeviceUnusedFallbackKeyTypes = e2ee?.devDeviceUnusedFallbackKeyTypes,
                rooms = RoomsSyncResponse(join = join, invite = invite, leave = leave, knock = knock),
                users = extensions?.profileUpdates?.toProfileUpdates(),
        )
    }

    /** MSC4262 splits removals into their own list; MSC4429 expresses them as null field values. */
    private fun Map<String, SlidingSyncProfileUpdate?>.toProfileUpdates(): Map<String, UserProfileSyncUpdate> {
        return mapValues { (_, update) ->
            UserProfileSyncUpdate(
                    profileUpdates = update?.let { it.updated.orEmpty() + it.removed.orEmpty().associateWith { null } }
            )
        }
    }

    private fun SlidingSyncRoom.membership(): Membership? {
        when (membership) {
            "invite" -> return Membership.INVITE
            "knock" -> return Membership.KNOCK
            "leave" -> return Membership.LEAVE
            "ban" -> return Membership.BAN
            "join" -> return Membership.JOIN
        }
        // Synapse does not send the MSC's optional membership field at all, so our own member event — which
        // the $ME sentinel always asks for — is what says whether we are still in the room. Without this a
        // room we were kicked from reads as joined, and gets marked as participating again.
        requiredState.orEmpty()
                .lastOrNull { it.type == EventType.STATE_ROOM_MEMBER && it.stateKey == userId }
                ?.getFixedRoomMemberContent()
                ?.membership
                ?.let { return it }
        return if (strippedStateEvents != null) Membership.INVITE else null
    }

    private fun SlidingSyncRoom.toRoomSync(accountData: List<Event>?, ephemeral: List<Event>): RoomSync {
        return RoomSync(
                // required_state is the room state as of the end of the timeline, which is exactly what
                // MSC4222's state_after means — so the handler applies it in the right order for free.
                stateAfterStable = requiredState?.let { RoomSyncState(events = it) },
                timeline = timeline?.let {
                    RoomSyncTimeline(events = it, limited = limited, prevToken = prevBatch)
                },
                summary = RoomSyncSummary(
                        // Sync v2's m.heroes excludes us by definition and the name/avatar resolvers rely on
                        // that; MSC4186 heroes do not, so a DM would otherwise be named after ourselves.
                        heroes = heroes.orEmpty().map { it.userId }.filter { it != userId },
                        joinedMembersCount = joinedCount,
                        invitedMembersCount = invitedCount,
                ),
                unreadNotifications = unreadCounts,
                ephemeral = ephemeral.takeIf { it.isNotEmpty() }
                        ?.let { LazyRoomSyncEphemeral.Parsed(RoomSyncEphemeral(events = it)) },
                accountData = accountData?.let { RoomSyncAccountData(events = it) },
                isInitialDelivery = initial,
                heroProfiles = heroes.orEmpty()
                        .filter { it.userId != userId }
                        .map { RoomSyncHeroProfile(userId = it.userId, displayName = it.displayName, avatarUrl = it.avatarUrl) },
        )
    }
}
