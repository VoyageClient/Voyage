/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.database.sql.store

import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.SessionScope
import javax.inject.Inject

/**
 * Single injectable facade bundling every session SQL store, wired in dependency order. Consumers
 * (sync handlers, data sources, tasks) inject this in place of Monarchy/Realm and run mutations inside
 * [org.matrix.android.sdk.internal.database.sqldelight.awaitDbTransaction] on the session dispatcher.
 */
@SessionScope
internal class SessionStores @Inject constructor(
        @SessionDatabase val database: SessionSqlDatabase,
) {
    val event = EventSqlStore(database)
    val user = UserSqlStore(database)
    val roomMember = RoomMemberSqlStore(database)
    val room = RoomSqlStore(database)
    val readReceipt = ReadReceiptSqlStore(database)
    val draft = DraftSqlStore(database)
    val roomTag = RoomTagSqlStore(database)
    val liveLocation = LiveLocationSqlStore(database)
    val annotations = EventAnnotationsSqlStore(database, event, liveLocation)
    val timelineEvent = TimelineEventSqlStore(database, event, annotations, readReceipt)
    val chunk = ChunkSqlStore(database)
    val space = SpaceSqlStore(database)
    val roomSummary = RoomSummarySqlStore(database, space, roomTag, draft, timelineEvent, user)
    val eventInsert = EventInsertSqlStore(database)
    val currentStateEvent = CurrentStateEventSqlStore(database, event)
    val threadSummary = ThreadSummarySqlStore(database, event)
    val homeServerCapabilities = HomeServerCapabilitiesSqlStore(database)
    val pushers = PushersSqlStore(database)
    val pushRules = PushRulesSqlStore(database)
    val localRoomSummary = LocalRoomSummarySqlStore(database, roomSummary)
    val filter = FilterSqlStore(database)
    val syncToken = SyncTokenSqlStore(database)
    val breadcrumbs = BreadcrumbsSqlStore(database)
    val readMarker = ReadMarkerSqlStore(database)
    val integrationManager = IntegrationManagerSqlStore(database)
    val previewUrlCache = PreviewUrlCacheSqlStore(database)
    val accountData = AccountDataSqlStore(database)
    val threePid = ThreePidSqlStore(database)
    val pollHistory = PollHistorySqlStore(database)
    val syncFilterParams = SyncFilterParamsSqlStore(database)
    val timelineWriter = TimelineSqlWriter(this)
}
