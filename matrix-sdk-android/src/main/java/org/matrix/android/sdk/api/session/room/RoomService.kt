/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.api.session.room

import kotlinx.coroutines.flow.Flow
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.identity.model.SignInvitationResult
import org.matrix.android.sdk.api.session.room.alias.RoomAliasDescription
import org.matrix.android.sdk.api.session.room.members.ChangeMembershipState
import org.matrix.android.sdk.api.session.room.model.LocalRoomSummary
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomParams
import org.matrix.android.sdk.api.session.room.peeking.PeekResult
import org.matrix.android.sdk.api.session.room.summary.RoomAggregateNotificationCount
import org.matrix.android.sdk.api.util.Optional

/**
 * This interface defines methods to get rooms. It's implemented at the session level.
 */
interface RoomService {

    /**
     * Create a room asynchronously.
     */
    suspend fun createRoom(createRoomParams: CreateRoomParams): String

    /**
     * Create a room locally.
     * This room will not be synchronized with the server and will not come back from the sync, so all the events related to this room will be generated
     * locally.
     */
    suspend fun createLocalRoom(createRoomParams: CreateRoomParams): String

    /**
     * Delete a local room with all its related events.
     */
    suspend fun deleteLocalRoom(roomId: String)

    /**
     * Create a direct room asynchronously. This is a facility method to create a direct room with the necessary parameters.
     */
    suspend fun createDirectRoom(otherUserId: String): String {
        return createRoom(
                CreateRoomParams()
                        .apply {
                            invitedUserIds.add(otherUserId)
                            setDirectMessage()
                            enableEncryptionIfInvitedUsersSupportIt = true
                        }
        )
    }

    /**
     * The formatted (HTML) body the SDK would derive for [text] using the same markdown + mention/pill
     * rendering as a normal message send, or null when it would be plain. Session-level (no [Room]
     * instance needed) so callers can format arbitrary text such as the topic of a room they haven't joined.
     */
    fun computeFormattedHtml(text: CharSequence, autoMarkdown: Boolean): String?

    /**
     * Join a room by id.
     * @param roomIdOrAlias the roomId or the room alias of the room to join
     * @param reason optional reason for joining the room
     * @param viaServers the servers to attempt to join the room through. One of the servers must be participating in the room.
     */
    suspend fun joinRoom(
            roomIdOrAlias: String,
            reason: String? = null,
            viaServers: List<String> = emptyList()
    )

    /**
     * @param roomId the roomId of the room to join
     * @param reason optional reason for joining the room
     * @param thirdPartySigned A signature of an m.third_party_invite token to prove that this user owns a third party identity
     * which has been invited to the room.
     */
    suspend fun joinRoom(
            roomId: String,
            reason: String? = null,
            thirdPartySigned: SignInvitationResult
    )

    /**
     * Knock on a room (request to join a room whose join rule is "knock").
     * @param roomIdOrAlias the roomId or the room alias of the room to knock on
     * @param reason optional reason shown to the room members reviewing the request
     * @param viaServers the servers to attempt to knock through. One of the servers must be participating in the room.
     */
    suspend fun knock(
            roomIdOrAlias: String,
            reason: String? = null,
            viaServers: List<String> = emptyList()
    )

    /**
     * Leave the room, or reject an invitation.
     * @param roomId the roomId of the room to leave
     * @param reason optional reason for leaving the room
     */
    suspend fun leaveRoom(roomId: String, reason: String? = null)

    /**
     * Get a room from a roomId.
     * @param roomId the roomId to look for.
     * @return a room with roomId or null
     */
    fun getRoom(roomId: String): Room?

    /**
     * Get a roomSummary from a roomId or a room alias.
     * @param roomIdOrAlias the roomId or the alias of a room to look for.
     * @return a matching room summary or null
     */
    fun getRoomSummary(roomIdOrAlias: String): RoomSummary?

    /**
     * A live [RoomSummary] associated with the room with id [roomId].
     * You can observe this summary to get dynamic data from this room, even if the room is not joined yet
     */
    fun getRoomSummaryFlow(roomId: String): Flow<Optional<RoomSummary>>

    /**
     * A live [LocalRoomSummary] associated with the room with id [roomId].
     * You can observe this summary to get dynamic data from this room, even if the room is not joined yet
     */
    fun getLocalRoomSummaryFlow(roomId: String): Flow<Optional<LocalRoomSummary>>

    /**
     * Get a snapshot list of room summaries.
     * @return the immutable list of [RoomSummary]
     */
    fun getRoomSummaries(
            queryParams: RoomSummaryQueryParams,
            sortOrder: RoomSortOrder = RoomSortOrder.NONE
    ): List<RoomSummary>

    /**
     * Get a live list of room summaries. This list is refreshed as soon as the data changes.
     * @return the [LiveData] of List[RoomSummary]
     */
    fun getRoomSummariesFlow(
            queryParams: RoomSummaryQueryParams,
            sortOrder: RoomSortOrder = RoomSortOrder.ACTIVITY
    ): Flow<List<RoomSummary>>

    /**
     * Get a snapshot list of Breadcrumbs.
     * @param queryParams parameters to query the room summaries. It can be use to keep only joined rooms, for instance.
     * @return the immutable list of [RoomSummary]
     */
    fun getBreadcrumbs(queryParams: RoomSummaryQueryParams): List<RoomSummary>

    /**
     * Get a live list of Breadcrumbs.
     * @param queryParams parameters to query the room summaries. It can be use to keep only joined rooms, for instance.
     * @return the [LiveData] of [RoomSummary]
     */
    fun getBreadcrumbsFlow(queryParams: RoomSummaryQueryParams): Flow<List<RoomSummary>>

    /**
     * Inform the Matrix SDK that a room is displayed.
     * The SDK will update the breadcrumbs in the user account data
     */
    suspend fun onRoomDisplayed(roomId: String)

    /**
     * Mark all rooms as read.
     */
    suspend fun markAllAsRead(roomIds: List<String>)

    /**
     * Resolve a room alias to a room ID.
     */
    suspend fun getRoomIdByAlias(
            roomAlias: String,
            searchOnServer: Boolean
    ): Optional<RoomAliasDescription>

    /**
     * Delete a room alias.
     */
    suspend fun deleteRoomAlias(roomAlias: String)

    /**
     * Return the current local changes membership for the given room.
     * see [getChangeMembershipsFlow] for more details.
     */
    fun getChangeMemberships(roomIdOrAlias: String): ChangeMembershipState

    /**
     * Return a live data of all local changes membership that happened since the session has been opened.
     * It allows you to track this in your client to known what is currently being processed by the SDK.
     * It won't know anything about change being done in other client.
     * Keys are roomId or roomAlias, depending of what you used as parameter for the join/leave action
     */
    fun getChangeMembershipsFlow(): Flow<Map<String, ChangeMembershipState>>

    /**
     * Return the roomId of an existing DM with the other user, or null if such room does not exist.
     * A room is a DM if:
     *  - it is listed in the `m.direct` account data
     *  - the current user has joined the room
     *  - the other user is invited or has joined the room
     *  - it has exactly 2 members
     * Note:
     *  - the returning room can be encrypted or not
     *  - the power level of the users are not taken into account. Normally in a DM, the 2 members are admins of the room
     */
    fun getExistingDirectRoomWithUser(otherUserId: String): String?

    /**
     * Get a room member for the tuple {userId,roomId}.
     * @param userId the userId to look for.
     * @param roomId the roomId to look for.
     * @return the room member or null
     */
    fun getRoomMember(userId: String, roomId: String): RoomMemberSummary?

    /**
     * Return the ids of the rooms we're in (join/invite) that have [userId] as an active member.
     * Backed by a single indexed lookup, unlike iterating [getRoomMember] over every room.
     */
    fun getRoomIdsWithUserActiveMembership(userId: String): List<String>

    /**
     * Observe a live room member for the tuple {userId,roomId}.
     * @param userId the userId to look for.
     * @param roomId the roomId to look for.
     * @return a LiveData of the optional found room member
     */
    fun getRoomMemberFlow(userId: String, roomId: String): Flow<Optional<RoomMemberSummary>>

    /**
     * Get some state events about a room.
     */
    suspend fun getRoomState(roomId: String): List<Event>

    /**
     * Use this if you want to get information from a room that you are not yet in (or invited).
     * It might be possible to get some information on this room if it is public or if guest access is allowed.
     * This call will try to gather some information on this room, but it could fail and get nothing more.
     */
    suspend fun peekRoom(roomIdOrAlias: String): PeekResult

    /**
     * Return a LiveData on the number of rooms.
     * @param queryParams parameters to query the room summaries. It can be use to keep only joined rooms, for instance.
     */
    fun getRoomCountFlow(queryParams: RoomSummaryQueryParams): Flow<Int>

    /**
     * TODO Doc.
     */
    fun getNotificationCountForRooms(queryParams: RoomSummaryQueryParams): RoomAggregateNotificationCount

    fun getFlattenRoomSummaryChildrenOf(spaceId: String?, memberships: List<Membership> = Membership.activeMemberships()): List<RoomSummary>

    /**
     * Refreshes the RoomSummary LatestPreviewContent for the given @param roomId.
     * If the roomId is null, all rooms are updated.
     *
     * This is useful for refreshing summary content with encrypted messages after receiving new room keys.
     */
    fun refreshJoinedRoomSummaryPreviews(roomId: String?)

    /**
     * Recompute the RoomSummary display name and avatar for the given @param roomId.
     * If the roomId is null, all joined rooms are updated.
     *
     * This is useful after changing a client-side setting that influences how they are computed,
     * such as forcing the contact display for group DMs.
     */
    fun refreshJoinedRoomSummaryDisplay(roomId: String?)
}
