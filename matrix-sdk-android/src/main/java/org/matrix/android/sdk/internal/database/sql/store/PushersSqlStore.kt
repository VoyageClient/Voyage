/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.api.session.pushers.PusherState
import org.matrix.android.sdk.internal.database.model.PusherDataEntity
import org.matrix.android.sdk.internal.database.model.PusherEntity
import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.database.sql.Pusher as PusherRow

/** SQL access for `pusher` (the 1-1 PusherDataEntity is flattened into data_url/data_format). */
internal class PushersSqlStore(private val database: SessionSqlDatabase) {

    private val queries get() = database.pusherQueries

    fun getAll(): List<PusherEntity> = queries.selectAll().executeAsList().map { it.toEntity() }

    fun getByPushKey(pushKey: String): List<PusherEntity> = queries.selectByPushKey(pushKey).executeAsList().map { it.toEntity() }

    fun insert(entity: PusherEntity) = queries.insert(
            push_key = entity.pushKey,
            kind = entity.kind,
            app_id = entity.appId,
            app_display_name = entity.appDisplayName,
            device_display_name = entity.deviceDisplayName,
            profile_tag = entity.profileTag,
            lang = entity.lang,
            data_url = entity.data?.url,
            data_format = entity.data?.format,
            enabled = if (entity.enabled) 1L else 0L,
            device_id = entity.deviceId,
            state_str = entity.state.name,
    )

    fun updateState(pushKey: String, state: PusherState) = queries.updateStateByPushKey(state.name, pushKey)

    fun deleteByPushKey(pushKey: String) = queries.deleteByPushKey(pushKey)

    fun deleteAll() = queries.deleteAll()

    /** Replace any pusher(s) with the same push key by the given entity. */
    fun replaceByPushKey(entity: PusherEntity) {
        queries.deleteByPushKey(entity.pushKey)
        insert(entity)
    }

    private fun PusherRow.toEntity(): PusherEntity = PusherEntity(
            pushKey = push_key,
            kind = kind,
            appId = app_id,
            appDisplayName = app_display_name,
            deviceDisplayName = device_display_name,
            profileTag = profile_tag,
            lang = lang,
            data = if (data_url != null || data_format != null) PusherDataEntity(data_url, data_format) else null,
            enabled = enabled != 0L,
            deviceId = device_id,
    ).also {
        it.state = runCatching { PusherState.valueOf(state_str) }.getOrDefault(PusherState.UNREGISTERED)
    }
}
