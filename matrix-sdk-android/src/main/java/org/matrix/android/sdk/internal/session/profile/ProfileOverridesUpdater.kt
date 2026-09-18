/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.profile

import dagger.Lazy
import org.matrix.android.sdk.api.session.accountdata.EncryptedAccountDataService
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.profile.ProfileOverrides
import org.matrix.android.sdk.internal.database.mapper.ContentMapper
import org.matrix.android.sdk.internal.database.sql.store.SessionStores
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.session.room.summary.SqlRoomSummaryUpdater
import javax.inject.Inject

/** Re-reads the persisted profile-overrides account data, applies it and refreshes affected room summaries. */
internal class ProfileOverridesUpdater @Inject constructor(
        @SessionId private val sessionId: String,
        private val stores: SessionStores,
        private val roomSummaryUpdater: SqlRoomSummaryUpdater,
        private val encryptedAccountDataService: Lazy<EncryptedAccountDataService>,
        private val extendedProfileCache: ExtendedProfileCache,
) {

    fun apply() {
        val old = ProfileOverrides.overrides
        val stored = stores.storedProfileOverrides(encryptedAccountDataService.get())
        val new = ProfileOverrides.parse(stored?.content, stored?.encrypted == true)
        if (new == old) return
        if (!ProfileOverrides.set(sessionId, new)) return
        val changedUsers = (old.keys + new.keys).filter { old[it] != new[it] }
        extendedProfileCache.notifyOverridesChanged(changedUsers)
        stores.roomSummary.roomIdsWithActiveMembers(changedUsers).forEach {
            roomSummaryUpdater.refreshDisplay(stores, it)
        }
    }
}

internal data class StoredProfileOverrides(val content: Content, val encrypted: Boolean)

internal fun SessionStores.storedProfileOverrides(encryption: EncryptedAccountDataService): StoredProfileOverrides? {
    val stored = ProfileOverrides.ACCOUNT_DATA_TYPES.firstNotNullOfOrNull { type ->
        ContentMapper.map(accountData.getUserAccountData(type)?.contentStr)?.let { type to it }
    } ?: return null
    return if (encryption.isEncrypted(stored.second)) {
        encryption.decryptOrNull(stored.first, stored.second)?.let { StoredProfileOverrides(it, encrypted = true) }
    } else {
        StoredProfileOverrides(stored.second, encrypted = false)
    }
}

internal fun SessionStores.hasLockedProfileOverrides(encryption: EncryptedAccountDataService): Boolean =
        !encryption.hasAccountDataKey() && ProfileOverrides.ACCOUNT_DATA_TYPES.firstNotNullOfOrNull { type ->
            ContentMapper.map(accountData.getUserAccountData(type)?.contentStr)
        }?.let(encryption::isEncrypted) == true
