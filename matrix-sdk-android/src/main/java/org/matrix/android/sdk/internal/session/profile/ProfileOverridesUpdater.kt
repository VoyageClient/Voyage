/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.profile

import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.profile.ProfileOverrides
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.session.room.summary.SqlRoomSummaryUpdater
import javax.inject.Inject

/** Applies a changed `im.voyage.setting.profile_overrides` content and refreshes affected room summaries. */
internal class ProfileOverridesUpdater @Inject constructor(
        private val stores: SessionStores,
        private val roomSummaryUpdater: SqlRoomSummaryUpdater,
) {

    fun apply(content: Content?) {
        val old = ProfileOverrides.overrides
        val new = ProfileOverrides.parse(content)
        if (new == old) return
        ProfileOverrides.set(new)
        val changedUsers = (old.keys + new.keys).filter { old[it] != new[it] }
        stores.roomSummary.roomIdsWithActiveMembers(changedUsers).forEach {
            roomSummaryUpdater.refreshDisplay(stores, it)
        }
    }
}
