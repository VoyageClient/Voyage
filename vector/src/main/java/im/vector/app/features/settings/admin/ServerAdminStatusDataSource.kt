/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.settings.admin

import im.vector.app.core.di.ActiveSessionHolder
import org.matrix.android.sdk.api.session.accountdata.UserAccountDataTypes
import org.matrix.android.sdk.api.session.admin.ServerAdminStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caches whether the user administers their homeserver, in account data so the probe is shared
 * across devices. There is no spec'd way to ask, so this is a Synapse-only probe whose result may
 * legitimately be [ServerAdminStatus.UNKNOWN] — which must never be shown to the user as "no".
 */
@Singleton
class ServerAdminStatusDataSource @Inject constructor(
        private val activeSessionHolder: ActiveSessionHolder,
) {

    fun cachedStatus(): ServerAdminStatus {
        val session = activeSessionHolder.getSafeActiveSession() ?: return ServerAdminStatus.UNKNOWN
        return parse(session.accountDataService().getUserAccountDataEvent(UserAccountDataTypes.TYPE_SERVER_ADMIN)?.content)
    }

    // An inconclusive probe isn't persisted, so without this a server that can't answer would be
    // re-probed on every visit to the settings screen.
    @Volatile private var probedThisSession = false

    /** Probes only when the answer isn't already known, so ordinary startups cost nothing. */
    suspend fun refreshIfUnknown(): ServerAdminStatus {
        val cached = cachedStatus()
        if (cached != ServerAdminStatus.UNKNOWN || probedThisSession) return cached
        return refresh()
    }

    suspend fun refresh(): ServerAdminStatus {
        val session = activeSessionHolder.getSafeActiveSession() ?: return ServerAdminStatus.UNKNOWN
        val status = session.adminService().probeServerAdminStatus()
        probedThisSession = true
        // Only a definitive answer is worth storing. Writing UNKNOWN back would leave cachedStatus()
        // reading UNKNOWN forever, so every visit would re-probe and push a no-op account-data
        // update to every other device.
        if (status != ServerAdminStatus.UNKNOWN) {
            session.accountDataService().updateUserAccountData(
                    UserAccountDataTypes.TYPE_SERVER_ADMIN,
                    mapOf(STATUS_KEY to status.name)
            )
        }
        return status
    }

    private fun parse(content: Map<String, Any>?): ServerAdminStatus {
        return ServerAdminStatus.fromValue(content?.get(STATUS_KEY) as? String)
    }

    companion object {
        private const val STATUS_KEY = "status"
    }
}
