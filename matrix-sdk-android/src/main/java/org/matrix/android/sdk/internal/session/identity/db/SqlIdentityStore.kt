/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package org.matrix.android.sdk.internal.session.identity.db

import org.matrix.android.sdk.api.session.identity.ThreePid
import org.matrix.android.sdk.api.session.identity.toMedium
import org.matrix.android.sdk.internal.di.IdentityDatabase
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.identity.data.IdentityData
import org.matrix.android.sdk.internal.session.identity.data.IdentityPendingBinding
import org.matrix.android.sdk.internal.session.identity.data.IdentityStore
import org.matrix.android.sdk.internal.session.identity.model.IdentityHashDetailResponse
import javax.inject.Inject

@SessionScope
internal class SqlIdentityStore @Inject constructor(
        @IdentityDatabase private val database: IdentitySqlDatabase,
) : IdentityStore {

    private val identityDataQueries get() = database.identityDataQueries
    private val pendingBindingQueries get() = database.identityPendingBindingQueries

    override fun getIdentityData(): IdentityData? =
            identityDataQueries.selectFirst().executeAsOneOrNull()?.let {
                IdentityData(
                        identityServerUrl = it.identity_server_url,
                        token = it.token,
                        hashLookupPepper = it.hash_lookup_pepper,
                        hashLookupAlgorithm = it.hash_lookup_algorithm.toAlgorithmList(),
                        userConsent = it.user_consent != 0L,
                )
            }

    override fun setUrl(url: String?) {
        // Changing the identity server resets everything, including any pending bindings.
        database.transaction {
            identityDataQueries.deleteAll()
            pendingBindingQueries.deleteAll()
            if (url != null) {
                identityDataQueries.insertUrl(url)
            }
        }
    }

    override fun setToken(token: String?) {
        identityDataQueries.setToken(token)
    }

    override fun setUserConsent(consent: Boolean) {
        identityDataQueries.setUserConsent(if (consent) 1L else 0L)
    }

    override fun setHashDetails(hashDetailResponse: IdentityHashDetailResponse) {
        identityDataQueries.setHashDetails(hashDetailResponse.pepper, hashDetailResponse.algorithms.toAlgorithmString())
    }

    override fun storePendingBinding(threePid: ThreePid, data: IdentityPendingBinding) {
        pendingBindingQueries.upsert(threePid.toPrimaryKey(), data.clientSecret, data.sendAttempt.toLong(), data.sid)
    }

    override fun getPendingBinding(threePid: ThreePid): IdentityPendingBinding? =
            pendingBindingQueries.selectByThreePid(threePid.toPrimaryKey()).executeAsOneOrNull()?.let {
                IdentityPendingBinding(
                        clientSecret = it.client_secret,
                        sendAttempt = it.send_attempt.toInt(),
                        sid = it.sid,
                )
            }

    override fun deletePendingBinding(threePid: ThreePid) {
        pendingBindingQueries.deleteByThreePid(threePid.toPrimaryKey())
    }

    private fun ThreePid.toPrimaryKey() = "${toMedium()}_$value"

    private fun String.toAlgorithmList(): List<String> = if (isEmpty()) emptyList() else split(ALGORITHM_SEPARATOR)

    private fun List<String>.toAlgorithmString(): String = joinToString(ALGORITHM_SEPARATOR)

    companion object {
        private const val ALGORITHM_SEPARATOR = "\n"
    }
}
