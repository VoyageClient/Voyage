/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.cache

import org.matrix.android.sdk.internal.database.sql.SessionSqlDatabase

/** Clears every session table (logout / clear-cache). */
internal class SqlClearCacheTask(private val database: SessionSqlDatabase) : ClearCacheTask {

    override suspend fun execute(params: Unit) {
        database.transaction {
            with(database.sessionDeleteAllQueries) {
                deleteAllBreadcrumbs()
                deleteAllChunk()
                deleteAllChunkStateEvent()
                deleteAllCurrentStateEvent()
                deleteAllDraft()
                deleteAllEditionOfEvent()
                deleteAllEvent()
                deleteAllEventAnnotationsSummary()
                deleteAllEventInsert()
                deleteAllFilter()
                deleteAllHomeServerCapabilities()
                deleteAllIgnoredUser()
                deleteAllLiveLocationShareAggregatedSummary()
                deleteAllLocalRoomSummary()
                deleteAllPendingThreePid()
                deleteAllPollHistoryStatus()
                deleteAllPollResponseAggregatedSummary()
                deleteAllPreviewUrlCache()
                deleteAllPushCondition()
                deleteAllPusher()
                deleteAllPushRule()
                deleteAllPushRules()
                deleteAllReactionAggregatedSummary()
                deleteAllReadMarker()
                deleteAllReadReceipt()
                deleteAllReadReceiptsSummary()
                deleteAllReferencesAggregatedSummary()
                deleteAllRoom()
                deleteAllRoomAccountData()
                deleteAllRoomMemberSummary()
                deleteAllRoomSummary()
                deleteAllRoomTag()
                deleteAllScalarToken()
                deleteAllSpaceChildSummary()
                deleteAllSpaceParentSummary()
                deleteAllSync()
                deleteAllSyncFilterParams()
                deleteAllThreadListPage()
                deleteAllThreadSummary()
                deleteAllTimelineEvent()
                deleteAllUser()
                deleteAllUserAccountData()
                deleteAllUserPresence()
                deleteAllUserThreePid()
                deleteAllWellknownIntegrationManagerConfig()
            }
        }
    }
}
