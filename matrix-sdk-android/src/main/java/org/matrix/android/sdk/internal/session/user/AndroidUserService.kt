/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.user

import androidx.lifecycle.LiveData
import androidx.paging.PagedList
import org.matrix.android.sdk.api.session.user.UserPagingService
import org.matrix.android.sdk.api.session.user.UserService
import org.matrix.android.sdk.api.session.user.model.User
import org.matrix.android.sdk.internal.session.SessionScope
import javax.inject.Inject

/**
 * The UserService the android app gets: [DefaultUserService] (plain-JVM) plus the paged user list,
 * which needs androidx.paging.
 */
@SessionScope
internal class AndroidUserService @Inject constructor(
        val delegate: DefaultUserService,
        private val userDataSource: UserDataSource,
) : UserService by delegate, UserPagingService {

    override fun getPagedUsersLive(filter: String?, excludedUserIds: Set<String>?): LiveData<PagedList<User>> {
        return userDataSource.getPagedUsersLive(filter, excludedUserIds)
    }
}
