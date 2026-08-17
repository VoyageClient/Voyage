/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.user.accountdata

import org.matrix.android.sdk.internal.di.MatrixScope
import javax.inject.Inject

/**
 * Users whose withheld content still has to be re-fetched after un-ignoring them, per account.
 *
 * Outlives the session on purpose. The un-ignore can be made from a screen holding a session that is
 * then released (an account switch closes its database), and the recovery is a whole sync of its own —
 * so whoever asked for it may well be gone before it can run. Kept here, the account's live session
 * picks it up on its next sync instead.
 */
@MatrixScope
internal class PendingUnIgnoreStore @Inject constructor() {

    private val pendingByUser = HashMap<String, MutableSet<String>>()

    @Synchronized
    fun add(userId: String, unIgnoredUserIds: Collection<String>) {
        if (unIgnoredUserIds.isEmpty()) return
        pendingByUser.getOrPut(userId) { LinkedHashSet() }.addAll(unIgnoredUserIds)
    }

    @Synchronized
    fun drain(userId: String): Set<String> = pendingByUser.remove(userId).orEmpty()
}
