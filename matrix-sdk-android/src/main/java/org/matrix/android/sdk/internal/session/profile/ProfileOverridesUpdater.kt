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
) {

    fun apply() {
        val old = ProfileOverrides.overrides
        val new = ProfileOverrides.parse(stores.storedProfileOverrides(encryptedAccountDataService.get()))
        if (new == old) return
        if (!ProfileOverrides.set(sessionId, new)) return
        val changedUsers = (old.keys + new.keys).filter { old[it] != new[it] }
        stores.roomSummary.roomIdsWithActiveMembers(changedUsers).forEach {
            roomSummaryUpdater.refreshDisplay(stores, it)
        }
    }
}

// A type whose MSC4483 payload cannot be decrypted (no ADK yet) falls through to the next one.
internal fun SessionStores.storedProfileOverrides(encryption: EncryptedAccountDataService): Content? =
        ProfileOverrides.ACCOUNT_DATA_TYPES.firstNotNullOfOrNull { type ->
            val content = ContentMapper.map(accountData.getUserAccountData(type)?.contentStr) ?: return@firstNotNullOfOrNull null
            if (encryption.isEncrypted(content)) encryption.decryptOrNull(type, content) else content
        }

internal fun SessionStores.hasLockedProfileOverrides(encryption: EncryptedAccountDataService): Boolean =
        !encryption.hasAccountDataKey() && ProfileOverrides.ACCOUNT_DATA_TYPES.any { type ->
            ContentMapper.map(accountData.getUserAccountData(type)?.contentStr)?.let(encryption::isEncrypted) == true
        }
