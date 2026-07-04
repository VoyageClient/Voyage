/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.search.index

import org.matrix.android.sdk.api.session.eventindex.EventIndexService
import org.matrix.android.sdk.api.session.eventindex.EventIndexStats
import javax.inject.Inject

internal class DefaultEventIndexService @Inject constructor(
        private val eventIndexer: EventIndexer,
        private val eventIndexStore: EventIndexStore,
) : EventIndexService {

    override fun isEnabled(): Boolean = eventIndexer.isEnabled()

    override fun setEnabled(enabled: Boolean) = eventIndexer.setEnabled(enabled)

    override fun setUnencryptedRoomsEnabled(enabled: Boolean) = eventIndexer.setUnencryptedRoomsEnabled(enabled)

    override suspend fun getStats(): EventIndexStats {
        val (events, rooms) = eventIndexStore.getStats()
        return EventIndexStats(eventCount = events, roomCount = rooms)
    }

    override suspend fun clearIndex() = eventIndexStore.clear()
}
