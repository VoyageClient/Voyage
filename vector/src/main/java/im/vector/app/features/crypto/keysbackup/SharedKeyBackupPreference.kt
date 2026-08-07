/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.crypto.keysbackup

import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import timber.log.Timber
import javax.inject.Inject

/**
 * The user's cross-device key backup preference (MSC4287), so a fresh sign-in doesn't re-enable
 * backup for someone who turned it off elsewhere.
 */
class SharedKeyBackupPreference @Inject constructor() {

    /**
     * Null when no usable preference is recorded, which per the MSC means the client is free to
     * decide for itself rather than assuming either value.
     */
    fun read(session: Session): Boolean? {
        val accountData = session.accountDataService()
        accountData.getUserAccountDataEvent(UserAccountDataTypes.TYPE_KEY_BACKUP)
                ?.content
                ?.get("enabled")
                ?.let { return it as? Boolean }
        // Element's pre-stabilisation form stores the opposite sense under a different type.
        return accountData.getUserAccountDataEvent(UserAccountDataTypes.TYPE_KEY_BACKUP_UNSTABLE)
                ?.content
                ?.get("disabled")
                ?.let { (it as? Boolean)?.not() }
    }

    suspend fun write(session: Session, enabled: Boolean) {
        try {
            session.accountDataService().updateUserAccountData(
                    UserAccountDataTypes.TYPE_KEY_BACKUP,
                    mapOf("enabled" to enabled)
            )
            session.accountDataService().updateUserAccountData(
                    UserAccountDataTypes.TYPE_KEY_BACKUP_UNSTABLE,
                    mapOf("disabled" to !enabled)
            )
        } catch (failure: Throwable) {
            // A preference we failed to publish must not fail the backup operation that triggered it.
            Timber.w(failure, "Failed to persist the shared key backup preference")
        }
    }
}
