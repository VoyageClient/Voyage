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
 * Android-only LiveData view over [UserService.getUserFlow], for consumers that observe via a
 * lifecycle owner. The service exposes a platform-neutral Flow so it can live in the shared core.
 */
fun UserService.getUserLive(userId: String): LiveData<Optional<User>> = getUserFlow(userId).asLiveData()

/**
 * Android-only paged user list, kept off [UserService] (which stays plain-JVM, no androidx.paging).
 * The android UserService impl also implements this; cast userService() to reach it.
 */
interface UserPagingService {

    fun getPagedUsersLive(filter: String? = null, excludedUserIds: Set<String>? = null): LiveData<PagedList<User>>
}
