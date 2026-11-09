/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.user

import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.paging.PagedList
import org.matrix.android.sdk.api.session.user.model.User
import org.matrix.android.sdk.api.util.Optional

/**
 * LiveData view over [UserService.getUserFlow], for consumers that observe via a lifecycle owner.
 */
fun UserService.getUserLive(userId: String): LiveData<Optional<User>> = getUserFlow(userId).asLiveData()

/**
 * Paged user list; cast userService() to reach it.
 */
interface UserPagingService {

    fun getPagedUsersLive(filter: String? = null, excludedUserIds: Set<String>? = null): LiveData<PagedList<User>>
}
