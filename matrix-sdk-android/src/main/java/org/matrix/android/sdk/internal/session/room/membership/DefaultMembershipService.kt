/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room.membership

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.matrix.android.sdk.api.query.QueryStringValue
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.events.model.content.EncryptedEventContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.identity.ThreePid
import org.matrix.android.sdk.api.session.room.members.MembershipService
import org.matrix.android.sdk.api.session.room.members.RoomMemberQueryParams
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomMemberSummary
import org.matrix.android.sdk.internal.crypto.model.SessionInfo
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.model.RoomMemberSummaryEntity
import org.matrix.android.sdk.internal.database.model.RoomMembersLoadStatusType
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.query.matches
import org.matrix.android.sdk.internal.session.room.RoomDataSource
import org.matrix.android.sdk.internal.session.room.membership.admin.MembershipAdminTask
import org.matrix.android.sdk.internal.session.room.membership.joining.InviteTask
import org.matrix.android.sdk.internal.session.room.membership.threepid.InviteThreePidTask

internal class DefaultMembershipService @AssistedInject constructor(
        @Assisted private val roomId: String,
        @SessionDatabase private val database: SessionSqlDatabase,
        @SessionDatabase private val dispatcher: CoroutineDispatcher,
        private val stores: SessionStores,
        private val loadRoomMembersTask: LoadRoomMembersTask,
        private val inviteTask: InviteTask,
        private val inviteThreePidTask: InviteThreePidTask,
        private val membershipAdminTask: MembershipAdminTask,
        private val roomDataSource: RoomDataSource,
        private val cryptoService: CryptoService,
        @UserId
        private val userId: String,
) : MembershipService {

    @AssistedFactory
    interface Factory {
        fun create(roomId: String): DefaultMembershipService
    }

    override suspend fun loadRoomMembersIfNeeded() {
        val params = LoadRoomMembersTask.Params(roomId, excludeMembership = Membership.LEAVE)
        loadRoomMembersTask.execute(params)
    }

    override suspend fun areAllMembersLoaded(): Boolean {
        val status = roomDataSource.getRoomMembersLoadStatus(roomId)
        return status == RoomMembersLoadStatusType.LOADED
    }

    override fun areAllMembersLoadedFlow(): Flow<Boolean> {
        return roomDataSource.getRoomMembersLoadStatusFlow(roomId)
    }

    override fun getRoomMember(userId: String): RoomMemberSummary? {
        return SqlRoomMemberHelper(stores, roomId).getLastRoomMember(userId)?.asDomain()
    }

    override fun getRoomMembers(queryParams: RoomMemberQueryParams): List<RoomMemberSummary> {
        return roomMembersFiltered(queryParams).map { it.asDomain() }
    }

    override fun getRoomMembersFlow(queryParams: RoomMemberQueryParams): Flow<List<RoomMemberSummary>> {
        return database.roomMemberSummaryQueries.selectByRoom(roomId).asFlow().mapToList(dispatcher)
                .map { roomMembersFiltered(queryParams).map { entity -> entity.asDomain() } }
    }

    private fun roomMembersFiltered(queryParams: RoomMemberQueryParams): List<RoomMemberSummaryEntity> {
        return stores.roomMember.getByRoom(roomId).filter { member ->
            queryParams.userId.matches(member.userId) &&
                    (queryParams.memberships.isEmpty() || member.membership in queryParams.memberships) &&
                    queryParams.displayName.matches(member.displayName) &&
                    (!queryParams.excludeSelf || member.userId != userId) &&
                    (queryParams.displayNameOrUserId == QueryStringValue.NoCondition ||
                            queryParams.displayNameOrUserId.matches(member.userId) ||
                            queryParams.displayNameOrUserId.matches(member.displayName))
        }
    }

    override fun getNumberOfJoinedMembers(): Int {
        return SqlRoomMemberHelper(stores, roomId).getNumberOfJoinedMembers()
    }

    override suspend fun ban(userId: String, reason: String?) {
        val params = MembershipAdminTask.Params(MembershipAdminTask.Type.BAN, roomId, userId, reason)
        membershipAdminTask.execute(params)
    }

    override suspend fun unban(userId: String, reason: String?) {
        val params = MembershipAdminTask.Params(MembershipAdminTask.Type.UNBAN, roomId, userId, reason)
        membershipAdminTask.execute(params)
    }

    override suspend fun kick(userId: String, reason: String?) {
        val params = MembershipAdminTask.Params(MembershipAdminTask.Type.KICK, roomId, userId, reason)
        membershipAdminTask.execute(params)
    }

    override suspend fun invite(userId: String, reason: String?) {
        sendShareHistoryKeysIfNeeded(userId)
        val params = InviteTask.Params(roomId, userId, reason)
        inviteTask.execute(params)
    }

    private suspend fun sendShareHistoryKeysIfNeeded(userId: String) {
        if (!cryptoService.isShareKeysOnInviteEnabled()) return
        cryptoService.sendSharedHistoryKeys(roomId, userId, sessionInfoSet = findLatestSessionInfo())
    }

    // The megolm sessions of the room's last forward chunk — shared with the invitee (MSC3061).
    private fun findLatestSessionInfo(): Set<SessionInfo>? {
        val chunkId = stores.chunk.lastForward(roomId)?.id ?: return null
        return stores.timelineEvent.getByChunk(chunkId).mapNotNull { timelineEvent ->
            timelineEvent.root?.asDomain()?.content?.toModel<EncryptedEventContent>()?.let { content ->
                val sessionId = content.sessionId ?: return@mapNotNull null
                val senderKey = content.senderKey ?: return@mapNotNull null
                SessionInfo(sessionId, senderKey)
            }
        }.toSet()
    }

    override suspend fun invite3pid(threePid: ThreePid) {
        val params = InviteThreePidTask.Params(roomId, threePid)
        return inviteThreePidTask.execute(params)
    }
}
