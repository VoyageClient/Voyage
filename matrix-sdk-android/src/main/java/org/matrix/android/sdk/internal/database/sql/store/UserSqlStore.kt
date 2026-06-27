/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.api.session.presence.model.PresenceEnum
import org.matrix.android.sdk.internal.database.model.UserEntity
import org.matrix.android.sdk.internal.database.model.presence.UserPresenceEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.User as UserRow
import org.matrix.android.sdk.internal.database.sql.User_presence as UserPresenceRow

/** SQL access for `user`, `ignored_user` and `user_presence`. */
internal class UserSqlStore(private val database: SessionSqlDatabase) {

    private val userQueries get() = database.userQueries
    private val ignoredQueries get() = database.ignoredUserQueries
    private val presenceQueries get() = database.userPresenceQueries

    fun getUser(userId: String): UserEntity? = userQueries.selectByUserId(userId).executeAsOneOrNull()?.toEntity()

    fun getUsers(userIds: Collection<String>): List<UserEntity> =
            userQueries.selectByUserIds(userIds).executeAsList().map { it.toEntity() }

    fun searchUsers(search: String): List<UserEntity> =
            userQueries.searchByDisplayName(search, search).executeAsList().map { it.toEntity() }

    fun upsertUser(entity: UserEntity) = userQueries.upsert(entity.userId, entity.displayName, entity.avatarUrl)

    fun deleteUser(userId: String) = userQueries.deleteByUserId(userId)

    fun getIgnoredUserIds(): List<String> = ignoredQueries.selectAll().executeAsList()

    fun insertIgnoredUser(userId: String) = ignoredQueries.insert(userId)

    fun deleteIgnoredUser(userId: String) = ignoredQueries.deleteByUserId(userId)

    fun getPresence(userId: String): UserPresenceEntity? = presenceQueries.selectByUserId(userId).executeAsOneOrNull()?.toEntity()

    fun upsertPresence(entity: UserPresenceEntity) = presenceQueries.upsert(
            user_id = entity.userId,
            last_active_ago = entity.lastActiveAgo,
            status_message = entity.statusMessage,
            is_currently_active = entity.isCurrentlyActive?.let { if (it) 1L else 0L },
            avatar_url = entity.avatarUrl,
            display_name = entity.displayName,
            presence_str = entity.presence.name,
    )
}

internal fun UserRow.toEntity(): UserEntity = UserEntity(
        userId = user_id,
        displayName = display_name,
        avatarUrl = avatar_url,
)

internal fun UserPresenceRow.toEntity(): UserPresenceEntity = UserPresenceEntity(
        userId = user_id,
        lastActiveAgo = last_active_ago,
        statusMessage = status_message,
        isCurrentlyActive = is_currently_active?.let { it != 0L },
        avatarUrl = avatar_url,
        displayName = display_name,
).also {
    it.presence = PresenceEnum.valueOf(presence_str)
}
