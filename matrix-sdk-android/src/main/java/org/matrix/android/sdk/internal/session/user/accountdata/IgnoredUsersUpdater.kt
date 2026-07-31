/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.user.accountdata

import kotlinx.coroutines.sync.Mutex
import org.matrix.android.sdk.internal.session.SessionScope
import javax.inject.Inject

/**
 * Serialises m.ignored_user_list updates and caches the set we last pushed to the server. Each
 * (un)ignore is a read-modify-write of the whole list, but the local DB only catches up when the
 * resulting sync arrives — so two quick updates both read the pre-sync list and the second resurrects
 * the user the first just removed. Basing each change on [lastKnownIds] instead fixes that. Invalidated
 * (set to null) whenever a sync rewrites the list, so external changes are picked up from the fresh DB.
 */
@SessionScope
internal class IgnoredUsersUpdater @Inject constructor() {
    val mutex = Mutex()

    @Volatile
    var lastKnownIds: Set<String>? = null
}
