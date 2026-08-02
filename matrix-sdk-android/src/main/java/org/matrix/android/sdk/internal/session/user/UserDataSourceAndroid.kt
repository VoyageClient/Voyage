/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.user

import androidx.lifecycle.LiveData
import androidx.paging.PagedList
import org.matrix.android.sdk.api.session.user.model.User
import org.matrix.android.sdk.internal.database.sqldelight.livePaged

// The PagedList user-directory surface; kept off UserDataSource so that stays android-free. The Flow
// accessors on the data source are primary; DefaultUserService (UserPagingService) uses this.
internal fun UserDataSource.getPagedUsersLive(filter: String?, excludedUserIds: Set<String>?): LiveData<PagedList<User>> {
    val query = if (filter.isNullOrEmpty()) {
        database.userQueries.selectAll()
    } else {
        database.userQueries.searchByDisplayName(filter, filter)
    }
    return livePaged(query, pageSize = 100) {
        query.executeAsList()
                .filter { excludedUserIds.isNullOrEmpty() || it.user_id !in excludedUserIds }
                .map { rowToUser(it) }
    }
}
