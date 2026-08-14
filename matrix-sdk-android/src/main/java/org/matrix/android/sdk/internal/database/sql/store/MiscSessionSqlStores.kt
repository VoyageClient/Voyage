/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.internal.database.model.FilterEntity
import org.matrix.android.sdk.internal.database.model.PendingThreePidEntity
import org.matrix.android.sdk.internal.database.model.PreviewUrlCacheEntity
import org.matrix.android.sdk.internal.database.model.RoomAccountDataEntity
import org.matrix.android.sdk.internal.database.model.UserAccountDataEntity
import org.matrix.android.sdk.internal.database.model.UserThreePidEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase

/** Single-row `filter` store. */
internal class FilterSqlStore(private val database: SessionSqlDatabase) {
    private val queries get() = database.filterQueries
    fun get(): FilterEntity? = queries.selectFirst().executeAsOneOrNull()?.let {
        FilterEntity(filterBodyJson = it.filter_body_json, roomEventFilterJson = it.room_event_filter_json, filterId = it.filter_id)
    }
    fun upsert(entity: FilterEntity) = queries.upsert(entity.filterBodyJson, entity.roomEventFilterJson, entity.filterId)
    fun updateFilterId(filterId: String) = queries.updateFilterId(filterId)
}

/** Single-row `sync` next-batch token store (plus the removed-rooms recovery marker row). */
internal class SyncTokenSqlStore(private val database: SessionSqlDatabase) {
    private val queries get() = database.syncQueries
    fun getNextBatch(): String? = queries.selectFirst().executeAsOneOrNull()?.next_batch
    fun setNextBatch(token: String?) = queries.upsert(token)
    fun isRemovedRoomsRecovered(): Boolean = queries.selectRemovedRoomsRecovered().executeAsOneOrNull() != null
    fun markRemovedRoomsRecovered() = queries.markRemovedRoomsRecovered()

    fun getSlidingSyncPos(): String? = queries.selectSlidingSyncPos().executeAsOneOrNull()?.next_batch
    fun setSlidingSyncPos(pos: String?) = queries.upsertSlidingSyncPos(pos)
    fun getSlidingSyncToDeviceSince(): String? = queries.selectSlidingSyncToDeviceSince().executeAsOneOrNull()?.next_batch
    fun setSlidingSyncToDeviceSince(since: String?) = queries.upsertSlidingSyncToDeviceSince(since)
    fun getSlidingSyncCoverage(): Int? = queries.selectSlidingSyncCoverage().executeAsOneOrNull()?.next_batch?.toIntOrNull()
    fun setSlidingSyncCoverage(rangeEnd: Int) = queries.upsertSlidingSyncCoverage(rangeEnd.toString())
    fun getSlidingSyncStateVersion(): String? = queries.selectSlidingSyncStateVersion().executeAsOneOrNull()?.next_batch
    fun setSlidingSyncStateVersion(version: String?) = queries.upsertSlidingSyncStateVersion(version)
}

/** Single-row `breadcrumbs` ordered recent-room-id list. */
internal class BreadcrumbsSqlStore(private val database: SessionSqlDatabase) {
    private val queries get() = database.breadcrumbsQueries
    fun get(): List<String> = queries.selectFirst().executeAsOneOrNull()?.recent_room_ids.splitToList()
    fun set(roomIds: List<String>) = queries.upsert(roomIds.joinToColumn())
}

/** `read_marker` per-room store. */
internal class ReadMarkerSqlStore(private val database: SessionSqlDatabase) {
    private val queries get() = database.readMarkerQueries
    fun get(roomId: String): String? = queries.selectByRoom(roomId).executeAsOneOrNull()?.event_id
    fun upsert(roomId: String, eventId: String) = queries.upsert(roomId, eventId)
    fun delete(roomId: String) = queries.deleteByRoom(roomId)
}

/** `scalar_token` + `wellknown_integration_manager_config` store. */
internal class IntegrationManagerSqlStore(private val database: SessionSqlDatabase) {
    private val scalarQueries get() = database.scalarTokenQueries
    private val wellknownQueries get() = database.wellknownIntegrationManagerConfigQueries

    fun getScalarToken(serverUrl: String): String? = scalarQueries.selectByServerUrl(serverUrl).executeAsOneOrNull()?.token
    fun upsertScalarToken(serverUrl: String, token: String) = scalarQueries.upsert(serverUrl, token)
    fun deleteScalarToken(serverUrl: String) = scalarQueries.deleteByServerUrl(serverUrl)

    fun getWellknownConfig(): Pair<String, String>? = wellknownQueries.selectAll().executeAsOneOrNull()?.let { it.api_url to it.ui_url }
    fun upsertWellknownConfig(apiUrl: String, uiUrl: String) = wellknownQueries.upsert(apiUrl, uiUrl)
}

/** `preview_url_cache` store. */
internal class PreviewUrlCacheSqlStore(private val database: SessionSqlDatabase) {
    private val queries get() = database.previewUrlCacheQueries
    fun get(url: String): PreviewUrlCacheEntity? = queries.selectByUrl(url).executeAsOneOrNull()?.let {
        PreviewUrlCacheEntity(
                url = it.url,
                urlFromServer = it.url_from_server,
                siteName = it.site_name,
                title = it.title,
                description = it.description,
                mxcUrl = it.mxc_url,
                imageWidth = it.image_width?.toInt(),
                imageHeight = it.image_height?.toInt(),
                lastUpdatedTimestamp = it.last_updated_timestamp,
        )
    }
    fun upsert(entity: PreviewUrlCacheEntity) = queries.upsert(
            url = entity.url,
            url_from_server = entity.urlFromServer,
            site_name = entity.siteName,
            title = entity.title,
            description = entity.description,
            mxc_url = entity.mxcUrl,
            image_width = entity.imageWidth?.toLong(),
            image_height = entity.imageHeight?.toLong(),
            last_updated_timestamp = entity.lastUpdatedTimestamp,
    )
    fun delete(url: String) = queries.deleteByUrl(url)
    fun deleteAll() = queries.deleteAll()
}

/** `user_account_data` + `room_account_data` store. */
internal class AccountDataSqlStore(private val database: SessionSqlDatabase) {
    private val userQueries get() = database.userAccountDataQueries
    private val roomQueries get() = database.roomAccountDataQueries

    fun getUserAccountData(): List<UserAccountDataEntity> = userQueries.selectAll().executeAsList().map {
        UserAccountDataEntity(type = it.type, contentStr = it.content_str)
    }
    fun getUserAccountData(type: String): UserAccountDataEntity? = userQueries.selectByType(type).executeAsOneOrNull()?.let {
        UserAccountDataEntity(type = it.type, contentStr = it.content_str)
    }
    fun upsertUserAccountData(type: String, contentStr: String?) = userQueries.upsert(type, contentStr)
    fun deleteUserAccountData(type: String) = userQueries.deleteByType(type)

    fun getRoomAccountData(roomId: String): List<RoomAccountDataEntity> = roomQueries.selectByRoom(roomId).executeAsList().map {
        RoomAccountDataEntity(type = it.type, contentStr = it.content_str)
    }
    fun upsertRoomAccountData(roomId: String, type: String, contentStr: String?) = roomQueries.upsert(roomId, type, contentStr)
    fun deleteRoomAccountData(roomId: String) = roomQueries.deleteByRoom(roomId)
}

/** `user_three_pid` + `pending_three_pid` replace-all store. */
internal class ThreePidSqlStore(private val database: SessionSqlDatabase) {
    private val userQueries get() = database.userThreePidQueries
    private val pendingQueries get() = database.pendingThreePidQueries

    fun getThreePids(): List<UserThreePidEntity> = userQueries.selectAll().executeAsList().map {
        UserThreePidEntity(medium = it.medium, address = it.address, validatedAt = it.validated_at, addedAt = it.added_at)
    }
    fun replaceThreePids(threePids: List<UserThreePidEntity>) {
        userQueries.deleteAll()
        threePids.forEach { userQueries.insert(it.medium, it.address, it.validatedAt, it.addedAt) }
    }

    fun getPendingThreePids(): List<PendingThreePidEntity> = pendingQueries.selectAll().executeAsList().map {
        PendingThreePidEntity(
                email = it.email, msisdn = it.msisdn, clientSecret = it.client_secret,
                sendAttempt = it.send_attempt.toInt(), sid = it.sid, submitUrl = it.submit_url,
        )
    }
    fun addPendingThreePid(entity: PendingThreePidEntity) =
            pendingQueries.insert(entity.email, entity.msisdn, entity.clientSecret, entity.sendAttempt.toLong(), entity.sid, entity.submitUrl)
    fun clearPendingThreePids() = pendingQueries.deleteAll()
}
