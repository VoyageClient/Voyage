/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.api.session.room.threads

import androidx.paging.PagedList

/**
 * Paged thread list; cast threadsService() to reach it.
 */
interface ThreadsPagingService {

    suspend fun getPagedThreadsList(userParticipating: Boolean, pagedListConfig: PagedList.Config): ThreadLivePageResult
}
